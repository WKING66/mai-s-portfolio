from collections.abc import AsyncGenerator, Sequence
from contextlib import aclosing
from json import dumps
from typing import cast
from uuid import UUID

from langchain_core.messages import AIMessageChunk, BaseMessage
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from app.billing import Billing, BillingRejected, Price
from app.config import Settings


class ModelGateway:
    def __init__(self, billing: Billing) -> None:
        self.billing = billing

    async def stream_chat(
        self,
        attempt_id: UUID,
        model: ChatOpenAI,
        messages: Sequence[BaseMessage],
        *,
        price: Price,
        input_limit: int,
        output_limit: int,
    ) -> AsyncGenerator[str]:
        # 1. 预留提交后才打开上游流，费用或数据库校验失败时不会发起调用。
        # 按完整消息（含角色、系统提示和历史）做保守的输入预检。
        # 兼容供应商仍须确认实际封装与计费上界，事后结算无法防止首笔超支。
        message_bytes = sum(
            len(
                dumps(
                    {"role": message.type, "content": message.content},
                    ensure_ascii=True,
                ).encode()
            )
            for message in messages
        )
        if message_bytes + 32 * (len(messages) + 1) > input_limit:
            raise BillingRejected("Chat input exceeds its verified billing bound.")
        reservation = await self.billing.reserve(
            attempt_id, "chat", price, input_limit, output_limit
        )
        # 2. 只发送访客可见正文，供应商的计费用量用于结算。
        usage = None
        async with aclosing(
            cast(
                AsyncGenerator[AIMessageChunk],
                model.astream(
                    list(messages),
                    max_completion_tokens=output_limit,
                    stream_usage=True,
                ),
            )
        ) as chunks:
            async for chunk in chunks:
                if chunk.usage_metadata is not None:
                    usage = chunk.usage_metadata
                if chunk.text:
                    yield chunk.text
        # 3. 按可信用量结算。上游中断或未报告用量时保留原预留。
        if usage is not None:
            await self.billing.settle(
                reservation, usage["input_tokens"], usage["output_tokens"]
            )

    async def embed(
        self,
        attempt_id: UUID,
        text: str,
        *,
        settings: Settings,
    ) -> list[float]:
        input_limit = settings.embedding_billable_input_limit
        dimensions = settings.embedding_dimensions
        # 缺少受支持的供应商配置时拒绝，避免 SDK 自动选择默认地址。
        if (
            settings.model_provider != "openai-compatible"
            or not settings.embedding_base_url
            or not settings.embedding_model
            or not settings.embedding_api_key.get_secret_value()
            or dimensions <= 0
        ):
            raise ValueError("Embedding deployment settings are incomplete.")
        # 拒绝空文本和超界输入，单次请求须落在已验证的计费范围内。
        if not text.strip() or len(text.encode("utf-8")) > input_limit:
            raise ValueError("Embedding input exceeds its configured bound.")
        # LangChain 只返回向量，缺少可信用量时保留预留。
        # 关闭自动拆分和重试，确保一次预留只对应一次上游调用。
        model = OpenAIEmbeddings(
            model=settings.embedding_model,
            base_url=settings.embedding_base_url,
            api_key=settings.embedding_api_key,
            dimensions=dimensions,
            timeout=settings.model_timeout_seconds,
            max_retries=0,
            check_embedding_ctx_length=False,
        )
        await self.billing.reserve(
            attempt_id,
            "embedding",
            Price(settings.embedding_input_price_per_million),
            input_limit,
            0,
        )
        vector = await model.aembed_query(text)
        # 不兼容维度不可交给检索索引，避免写入后无法查询。
        if len(vector) != dimensions:
            raise ValueError("Unexpected embedding dimensions.")
        return vector
