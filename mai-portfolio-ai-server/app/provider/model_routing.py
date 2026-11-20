import asyncio
from collections.abc import AsyncGenerator, Callable
from contextlib import aclosing
from dataclasses import dataclass
from time import monotonic
from typing import Any, cast
from urllib.parse import urlsplit

from httpx import AsyncClient, ConnectError, ConnectTimeout
from langchain_core.messages import AIMessageChunk, BaseMessage
from langchain_core.messages.ai import UsageMetadata
from langchain_core.outputs import ChatGenerationChunk
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from openai import APIConnectionError, APIStatusError, RateLimitError
from pydantic import PrivateAttr, SecretStr
from tenacity import AsyncRetrying, retry_if_exception, stop_after_attempt

from app.contracts.model_catalog import ChatParameters, EmbeddingParameters
from app.contracts.wire import ExecutionRejected

MODEL_RESPONSE_TIMEOUT = 5
CONTEXT_TOKENS = 32_768
MODEL_FAILURE_COOLDOWN = 60
MODEL_FREE_QUOTA_COOLDOWN = 300
MAX_ATTEMPTS = 4


@dataclass(frozen=True, slots=True)
class Candidate:
    model_id: int
    model_revision: int
    provider_revision: int
    model_key: str
    base_url: str
    api_key: SecretStr
    config: dict[str, int | bool]

    @property
    def revision_key(self) -> tuple[int, int, int]:
        return self.model_id, self.model_revision, self.provider_revision


def chat_extra_body(
    base_url: str, model_key: str, enable_think: bool | None,
) -> dict[str, bool | dict[str, str]] | None:
    host = urlsplit(base_url).hostname
    if enable_think is None:
        # 保留旧配置下百炼 qwen3.7-flash 的默认行为，避免思考耗尽输出额度。
        if host == "dashscope.aliyuncs.com" and model_key == "qwen3.7-flash":
            return {"enable_thinking": False}
        return None
    if host in {"open.bigmodel.cn", "api.z.ai"}:
        return {"thinking": {"type": "enabled" if enable_think else "disabled"}}
    return {"enable_thinking": enable_think}


def can_retry(error: BaseException) -> bool:
    # 连接建立失败没有发出请求。收到限流拒绝也可重试，等待回包超时则可能已在生成。
    return isinstance(error, RateLimitError) or (
        isinstance(error, APIConnectionError)
        and isinstance(error.__cause__, (ConnectError, ConnectTimeout))
    )


def fit_chat_context(messages: list[BaseMessage], output_tokens: int) -> list[BaseMessage]:
    # 当前链使用文本消息，逐字节上界给 UTF-8 文本及消息封装预留空间。
    available = CONTEXT_TOKENS - output_tokens
    trimmed = list(messages)
    while 32 + sum(len(message.text.encode("utf-8")) + 32 for message in trimmed) > available:
        if len(trimmed) < 4:
            raise ExecutionRejected(422, "CONTEXT_LIMIT", "模型上下文超过允许长度。")
        del trimmed[1:3]
    return trimmed


class ChatOpenAIWithThinkingProgress(ChatOpenAI):
    _on_thinking: Callable[[], None] | None = PrivateAttr(default=None)

    def track_thinking(self, callback: Callable[[], None] | None) -> None:
        self._on_thinking = callback

    def _convert_chunk_to_generation_chunk(
        self, chunk: dict[str, Any], default_chunk_class: type,
        base_generation_info: dict[str, Any] | None,
    ) -> ChatGenerationChunk | None:
        # LangChain 会丢弃兼容接口的非标准思考字段。在转换前只观察是否出现，后续事件不携带原文。
        if self._on_thinking is not None:
            choices = chunk.get("choices") or []
            delta = (choices[0].get("delta") or {}) if choices else {}
            if delta.get("reasoning_content") or delta.get("reasoning") or delta.get("reasoning_details"):
                self._on_thinking()
                self._on_thinking = None
        return super()._convert_chunk_to_generation_chunk(chunk, default_chunk_class, base_generation_info)


class ModelRouting:
    def __init__(self, http: AsyncClient) -> None:
        self.http = http
        self.retry_at: dict[tuple[int, int, int], float] = {}

    def available(self, candidates: list[Candidate]) -> list[Candidate]:
        now = monotonic()
        self.retry_at = {key: at for key, at in self.retry_at.items() if at > now}
        return [candidate for candidate in candidates if candidate.revision_key not in self.retry_at]

    def cool_down(self, candidate: Candidate, error: BaseException) -> None:
        # 免费额度耗尽明确属于当前模型。其余供应商错误也先按当前候选隔离。
        delay = MODEL_FAILURE_COOLDOWN
        if isinstance(error, APIStatusError) and error.code == "AllocationQuota.FreeTierOnly":
            delay = MODEL_FREE_QUOTA_COOLDOWN
        self.retry_at[candidate.revision_key] = monotonic() + delay

    async def stream_chat(
        self, candidates: list[Candidate], messages: list[BaseMessage],
        report_usage: Callable[[UsageMetadata | None], None] | None = None,
        *,
        on_thinking: Callable[[], None] | None = None,
    ) -> AsyncGenerator[AIMessageChunk]:
        for candidate in self.available(candidates):
            parameters = ChatParameters.model_validate(candidate.config)
            model = ChatOpenAIWithThinkingProgress(
                model=candidate.model_key,
                base_url=candidate.base_url,
                api_key=candidate.api_key,
                use_responses_api=False,
                extra_body=chat_extra_body(candidate.base_url, candidate.model_key, parameters.enable_think),
                max_retries=0,
                http_async_client=self.http,
            )
            model.track_thinking(on_thinking)
            try:
                context = fit_chat_context(messages, parameters.max_output_tokens)
            except ExecutionRejected:
                continue

            emitted = False
            output_limit_reached = False
            try:
                async for attempt in AsyncRetrying(
                    retry=retry_if_exception(can_retry),
                    stop=stop_after_attempt(MAX_ATTEMPTS),
                    reraise=True,
                ):
                    with attempt:
                        usage = None
                        stream = cast(
                            AsyncGenerator[AIMessageChunk],
                            model.astream(
                                context,
                                max_completion_tokens=parameters.max_output_tokens,
                                stream_usage=True,
                            ),
                        )
                        try:
                            async with aclosing(stream):
                                async with asyncio.timeout(MODEL_RESPONSE_TIMEOUT):
                                    first = await anext(stream, None)
                                if first is not None:
                                    usage = first.usage_metadata
                                    emitted = bool(first.text)
                                    output_limit_reached = first.response_metadata.get("finish_reason") == "length"
                                    yield first
                                async for chunk in stream:
                                    if chunk.usage_metadata is not None:
                                        usage = chunk.usage_metadata
                                    emitted = emitted or bool(chunk.text)
                                    output_limit_reached = output_limit_reached or (
                                        chunk.response_metadata.get("finish_reason") == "length"
                                    )
                                    yield chunk
                        except (TimeoutError, APIConnectionError, APIStatusError) as error:
                            if report_usage is not None and (
                                usage is not None or isinstance(error, TimeoutError)
                                or (isinstance(error, APIConnectionError) and not can_retry(error))
                                or (isinstance(error, APIStatusError) and error.status_code >= 500)
                            ):
                                report_usage(usage)
                            if not emitted:
                                raise
                            self.cool_down(candidate, error)
                            # 已有正文时结束本轮。业务错误不会触发模型请求重试。
                            raise ExecutionRejected(503, "MODEL_STREAM_INTERRUPTED", "模型输出已中断。") from error
                        if report_usage is not None:
                            report_usage(usage)
                if emitted:
                    self.retry_at.pop(candidate.revision_key, None)
                    return
                # 输出额度耗尽只影响本轮，下一轮仍可尝试该候选。
                if not output_limit_reached:
                    self.cool_down(candidate, TimeoutError())
            except (TimeoutError, APIConnectionError, APIStatusError) as error:
                self.cool_down(candidate, error)
        raise ExecutionRejected(503, "MODEL_UNAVAILABLE", "模型服务暂不可用。")

    async def embed(self, candidates: list[Candidate], text: str) -> list[float]:
        for candidate in self.available(candidates):
            parameters = EmbeddingParameters.model_validate(candidate.config)
            model = OpenAIEmbeddings(
                model=candidate.model_key,
                base_url=candidate.base_url,
                api_key=candidate.api_key,
                dimensions=parameters.dimensions,
                max_retries=0,
                http_async_client=self.http,
                check_embedding_ctx_length=False,
            )
            try:
                async for attempt in AsyncRetrying(
                    retry=retry_if_exception(can_retry),
                    stop=stop_after_attempt(MAX_ATTEMPTS),
                    reraise=True,
                ):
                    with attempt:
                        async with asyncio.timeout(MODEL_RESPONSE_TIMEOUT):
                            vector = await model.aembed_query(text)
                        if vector and len(vector) == parameters.dimensions:
                            self.retry_at.pop(candidate.revision_key, None)
                            return vector
                        self.cool_down(candidate, ValueError())
            except (TimeoutError, APIConnectionError, APIStatusError) as error:
                self.cool_down(candidate, error)
        raise ExecutionRejected(503, "EMBEDDING_UNAVAILABLE", "向量服务暂不可用。")
