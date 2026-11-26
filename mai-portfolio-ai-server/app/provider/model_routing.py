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
from openai import APIConnectionError, APIError, APIStatusError, RateLimitError
from pydantic import PrivateAttr, SecretStr
from tenacity import AsyncRetrying, retry_if_exception, stop_after_attempt

from app.chat_timing import SAFE_PROVIDER_CODES, ChatTiming, ModelAttemptTiming
from app.contracts.execution_diagnostics import ExecutionDiagnostics, FinishReason
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
    _timing: ModelAttemptTiming | None = PrivateAttr(default=None)
    _refused: bool = PrivateAttr(default=False)

    @property
    def refused(self) -> bool:
        return self._refused

    def track_timing(self, timing: ModelAttemptTiming | None) -> None:
        self._timing = timing
        self._refused = False

    def track_thinking(self, callback: Callable[[], None] | None) -> None:
        self._on_thinking = callback

    def _convert_chunk_to_generation_chunk(
        self, chunk: dict[str, Any], default_chunk_class: type,
        base_generation_info: dict[str, Any] | None,
    ) -> ChatGenerationChunk | None:
        # LangChain 会丢弃兼容接口的非标准思考字段。在转换前只观察是否出现，后续事件不携带原文。
        if self._timing is not None:
            self._timing.mark_frame()
        choices = chunk.get("choices") or []
        delta = (choices[0].get("delta") or {}) if choices else {}
        if delta.get("refusal"):
            self._refused = True
        if delta.get("reasoning_content") or delta.get("reasoning") or delta.get("reasoning_details"):
            if self._timing is not None:
                self._timing.mark_thinking()
            if self._on_thinking is not None:
                self._on_thinking()
                self._on_thinking = None
        return super()._convert_chunk_to_generation_chunk(chunk, default_chunk_class, base_generation_info)


def upstream_finish_reason(error: TimeoutError | APIError) -> FinishReason:
    if isinstance(error, TimeoutError):
        return FinishReason.FIRST_CHUNK_TIMEOUT
    if isinstance(error.code, str):
        if error.code in {"context_length_exceeded", "context_window_exceeded"}:
            return FinishReason.CONTEXT_LIMIT
        if error.code in SAFE_PROVIDER_CODES:
            return FinishReason.CAPACITY_LIMIT
        if error.code in {"content_filter", "content_policy_violation", "refusal"}:
            return FinishReason.UPSTREAM_REJECTED
    if isinstance(error, APIStatusError):
        if error.status_code in {429, 503, 529}:
            return FinishReason.CAPACITY_LIMIT
        if error.status_code < 500:
            return FinishReason.UPSTREAM_REJECTED
    return FinishReason.UNAVAILABLE


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
        timing: ChatTiming | None = None,
        diagnostics: ExecutionDiagnostics | None = None,
    ) -> AsyncGenerator[AIMessageChunk]:
        diagnostics = diagnostics if diagnostics is not None else ExecutionDiagnostics()
        available = self.available(candidates)
        if timing is not None:
            timing.log_candidates(
                [candidate.model_id for candidate in candidates],
                [candidate.model_id for candidate in available],
            )
        eligible: list[tuple[Candidate, ChatParameters, list[BaseMessage]]] = []
        reason = FinishReason.UNAVAILABLE
        for candidate in available:
            parameters = ChatParameters.model_validate(candidate.config)
            try:
                context = fit_chat_context(messages, parameters.max_output_tokens)
            except ExecutionRejected:
                reason = FinishReason.CONTEXT_LIMIT
            else:
                eligible.append((candidate, parameters, context))

        for index, (candidate, parameters, context) in enumerate(eligible):
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
            for attempt_number in range(1, MAX_ATTEMPTS + 1):
                await diagnostics.dispatch_allowed.wait()
                diagnostics.start(candidate.model_id, candidate.model_revision)
                probe = (
                    ModelAttemptTiming(timing, candidate.model_id, attempt_number)
                    if timing is not None else None
                )
                model.track_timing(probe)
                usage = None
                emitted = False
                finish_signal: FinishReason | None = None
                prefix: list[str] = []
                next_candidate = 1 if index + 1 < len(eligible) else 0
                try:
                    stream = cast(AsyncGenerator[AIMessageChunk], model.astream(
                        context, max_completion_tokens=parameters.max_output_tokens, stream_usage=True,
                    ))
                    async with aclosing(stream):
                        async with asyncio.timeout(MODEL_RESPONSE_TIMEOUT):
                            try:
                                chunk = await anext(stream, None)
                            except ValueError as error:
                                # LangChain 把首块前的正常 EOF 包装为此异常。
                                if str(error) != "No generation chunks were returned":
                                    raise
                                chunk = None
                        if probe is not None and chunk is not None:
                            probe.first_chunk_ms = probe.chat.elapsed_ms()
                        while chunk is not None:
                            content = chunk.text
                            visible = bool(content.strip())
                            diagnostics.observe(visible)
                            if probe is not None:
                                probe.mark_text(content)
                            if chunk.usage_metadata is not None:
                                usage = chunk.usage_metadata
                            upstream_reason = chunk.response_metadata.get("finish_reason")
                            if upstream_reason == "length":
                                finish_signal = FinishReason.OUTPUT_LIMIT
                            elif upstream_reason == "content_filter":
                                finish_signal = FinishReason.UPSTREAM_REJECTED
                            # 空白前缀与首段可见正文一起提交，切换候选时丢弃旧前缀。
                            if emitted or visible:
                                if prefix:
                                    chunk = chunk.model_copy(update={"content": "".join(prefix) + content})
                                    prefix.clear()
                                emitted = True
                                yield chunk
                            elif content:
                                prefix.append(content)
                            chunk = await anext(stream, None)
                except (TimeoutError, APIError) as error:
                    if probe is not None:
                        probe.mark_error(error)
                    stream_error = isinstance(error, APIError) and not isinstance(
                        error, (APIConnectionError, APIStatusError),
                    )
                    retriable = can_retry(error)
                    if report_usage is not None and (
                        usage is not None or stream_error or isinstance(error, TimeoutError)
                        or (isinstance(error, APIConnectionError) and not retriable)
                        or (isinstance(error, APIStatusError) and error.status_code >= 500)
                    ):
                        report_usage(usage)
                    if model.refused:
                        finish_signal = FinishReason.UPSTREAM_REJECTED
                    reason = finish_signal or upstream_finish_reason(error)
                    if emitted:
                        if reason in (FinishReason.FIRST_CHUNK_TIMEOUT, FinishReason.UNAVAILABLE):
                            reason = FinishReason.STREAM_INTERRUPTED
                        diagnostics.end(reason)
                        diagnostics.finish_reason = reason
                        self.cool_down(candidate, error)
                        raise ExecutionRejected(503, "MODEL_STREAM_INTERRUPTED", "模型输出已中断。") from error
                    if attempt_number < MAX_ATTEMPTS and retriable:
                        diagnostics.end(reason, 2)
                        continue
                    diagnostics.end(reason, next_candidate)
                    self.cool_down(candidate, error)
                    if stream_error and not next_candidate:
                        diagnostics.finish_reason = reason
                        raise
                    break
                except (asyncio.CancelledError, GeneratorExit) as error:
                    # 停止方保存的快照保留仍在途的尝试，不伪造上游结果。
                    if probe is not None:
                        probe.mark_error(error)
                    raise
                except Exception as error:
                    if probe is not None:
                        probe.mark_error(error)
                    diagnostics.end(FinishReason.INTERNAL_ERROR)
                    diagnostics.finish_reason = FinishReason.INTERNAL_ERROR
                    raise
                else:
                    if report_usage is not None:
                        report_usage(usage)
                    if model.refused:
                        finish_signal = FinishReason.UPSTREAM_REJECTED
                    reason = finish_signal or (FinishReason.COMPLETED if emitted else FinishReason.EMPTY_BODY)
                    if reason == FinishReason.COMPLETED:
                        diagnostics.end(reason)
                        diagnostics.finish_reason = reason
                        self.retry_at.pop(candidate.revision_key, None)
                        return
                    if emitted:
                        diagnostics.end(reason)
                        diagnostics.finish_reason = reason
                        raise ExecutionRejected(503, "MODEL_STREAM_INTERRUPTED", "模型输出已中断。")
                    diagnostics.end(reason, next_candidate)
                    if reason == FinishReason.EMPTY_BODY:
                        self.cool_down(candidate, TimeoutError())
                    break
                finally:
                    if probe is not None:
                        probe.log_finished()
        diagnostics.finish_reason = reason
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
