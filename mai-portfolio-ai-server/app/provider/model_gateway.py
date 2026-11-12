from collections.abc import AsyncGenerator, Sequence
from contextlib import aclosing
from json import dumps
from typing import cast
from uuid import UUID

from langchain_core.messages import AIMessageChunk, BaseMessage
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from app.config import Settings
from app.provider.model_budget_client import BillingRejected, ModelBudgetClient, Price
from app.provider.provider_http import provider_http_client


class ModelGateway:
    def __init__(self, budget: ModelBudgetClient) -> None:
        self.budget = budget

    async def stream_chat(
        self,
        attempt_id: UUID,
        model: ChatOpenAI,
        messages: Sequence[BaseMessage],
        *,
        price: Price,
        currency: str,
        price_version: str,
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
        await self.budget.reserve(
            attempt_id,
            price=price,
            input_limit=input_limit,
            output_limit=output_limit,
            currency=currency,
            price_version=price_version,
        )
        # 2. 只发送访客可见正文，供应商的计费用量用于结算。
        usage = None
        try:
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
        finally:
            # 供应商已经上报用量时，即使浏览器停止对话也要结算。未上报则保留原预占。
            if usage is not None:
                await self.budget.settle(
                    attempt_id, usage["input_tokens"], usage["output_tokens"]
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
        # 直接读取 SDK 的完整响应，向量与供应商回报的输入用量须来自同一次调用。
        # 关闭自动拆分和重试，确保一次预留只对应一次上游调用。
        async with provider_http_client() as provider_client:
            model = OpenAIEmbeddings(
                model=settings.embedding_model,
                base_url=settings.embedding_base_url,
                api_key=settings.embedding_api_key,
                dimensions=dimensions,
                timeout=settings.model_timeout_seconds,
                max_retries=0,
                check_embedding_ctx_length=False,
                http_async_client=provider_client,
            )
            await self.budget.reserve(
                attempt_id,
                price=Price(settings.embedding_input_price_per_million),
                input_limit=input_limit,
                output_limit=0,
                currency=settings.billing_currency,
                price_version=settings.price_version,
            )
            response = await model.async_client.create(
                input=[text], model=settings.embedding_model, dimensions=dimensions
            )
        if isinstance(response, dict):
            data = response["data"]
            usage = response.get("usage")
            input_tokens = usage.get("prompt_tokens") if usage else None
            vector = cast(list[float], data[0]["embedding"])
        else:
            input_tokens = response.usage.prompt_tokens if response.usage else None
            vector = cast(list[float], response.data[0].embedding)
        # 维度错误也可能产生费用，先用可信用量结算，再检查返回的向量。
        if type(input_tokens) is int and input_tokens > 0:
            await self.budget.settle(attempt_id, input_tokens, 0)
        # 不兼容维度不可交给检索索引，避免写入后无法查询。
        if len(vector) != dimensions:
            raise ValueError("Unexpected embedding dimensions.")
        return vector
