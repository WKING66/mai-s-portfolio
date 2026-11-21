import asyncio
from contextlib import aclosing

from cryptography.fernet import InvalidToken
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from openai import APIConnectionError, APIStatusError
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import Settings, decrypt_provider_key
from app.contracts.model_catalog import (
    ChatParameters,
    EmbeddingParameters,
    ModelConfig,
    ModelProvider,
    ModelType,
)
from app.contracts.wire import ExecutionRejected, WireModel
from app.provider.model_routing import MODEL_RESPONSE_TIMEOUT, chat_extra_body
from app.provider.provider_http import provider_http_client

CONNECTION_PROBE_TEXT = "请只回答：连接成功"
CHAT_PROBE_TIMEOUT = 10


class ModelProbeView(WireModel):
    ok: bool
    code: str | None = None
    dimensions: int | None = None


async def probe_model(
    session: AsyncSession, settings: Settings, model_id: int
) -> ModelProbeView:
    model = await session.get(ModelConfig, model_id)
    if model is None:
        raise ExecutionRejected(404, "MODEL_NOT_FOUND", "模型配置不存在。")
    provider = await session.get(ModelProvider, model.model_provider_id)
    if provider is None or provider.api_key is None:
        return ModelProbeView(ok=False, code="PROVIDER_KEY_REQUIRED")

    try:
        api_key = decrypt_provider_key(settings, provider.api_key)
    except (ValueError, InvalidToken):
        return ModelProbeView(ok=False, code="PROVIDER_KEY_UNAVAILABLE")

    # 外部调用前结束数据库读事务。测试不会修改目录、生效链或运行时的可用状态。
    model_type, config, model_key, base_url = (
        model.model_type, model.config, model.model_key, provider.base_url
    )
    await session.rollback()
    try:
        async with provider_http_client() as http:
            if model_type == ModelType.CHAT:
                parameters = ChatParameters.model_validate(config)
                chat = ChatOpenAI(
                    model=model_key,
                    base_url=base_url,
                    api_key=api_key,
                    max_retries=0,
                    use_responses_api=False,
                    extra_body=chat_extra_body(base_url, model_key, parameters.enable_think),
                    http_async_client=http,
                )
                async with aclosing(chat.astream(  # type: ignore[arg-type]
                    CONNECTION_PROBE_TEXT, max_completion_tokens=parameters.max_output_tokens
                )) as stream:
                    # 思考模型先返回推理片段，继续等到正文才判定通过。
                    async with asyncio.timeout(CHAT_PROBE_TIMEOUT):
                        async with asyncio.timeout(MODEL_RESPONSE_TIMEOUT):
                            first = await anext(stream, None)
                        if first is not None and first.text:
                            return ModelProbeView(ok=True)
                        async for chunk in stream:
                            if chunk.text:
                                return ModelProbeView(ok=True)
                return ModelProbeView(ok=False, code="EMPTY_MODEL_RESPONSE")

            parameters = EmbeddingParameters.model_validate(config)
            embedding = OpenAIEmbeddings(
                model=model_key,
                base_url=base_url,
                api_key=api_key,
                dimensions=parameters.dimensions,
                max_retries=0,
                http_async_client=http,
                check_embedding_ctx_length=False,
            )
            async with asyncio.timeout(MODEL_RESPONSE_TIMEOUT):
                vector = await embedding.aembed_query(CONNECTION_PROBE_TEXT)
            if not vector or parameters.dimensions not in (None, len(vector)):
                return ModelProbeView(ok=False, code="EMBEDDING_DIMENSION_MISMATCH", dimensions=len(vector))
            return ModelProbeView(ok=True, dimensions=len(vector))
    except TimeoutError:
        return ModelProbeView(ok=False, code="MODEL_TIMEOUT")
    except APIConnectionError:
        return ModelProbeView(ok=False, code="MODEL_CONNECTION_FAILED")
    except APIStatusError as error:
        # 只返回稳定状态。供应商原始错误可能包含凭证或调用细节。
        if error.code == "AllocationQuota.FreeTierOnly":
            return ModelProbeView(ok=False, code="MODEL_FREE_QUOTA_EXHAUSTED")
        return ModelProbeView(ok=False, code="MODEL_REQUEST_REJECTED")
