from collections.abc import AsyncGenerator, Sequence
from contextlib import aclosing
from typing import cast
from uuid import UUID

from langchain_core.messages import AIMessageChunk, BaseMessage
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from app.billing import Billing, Price
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
        reservation = await self.billing.reserve(
            attempt_id, "chat", price, input_limit, output_limit
        )
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
        if (
            settings.model_provider != "openai-compatible"
            or not settings.embedding_base_url
            or not settings.embedding_model
            or not settings.embedding_api_key.get_secret_value()
            or dimensions <= 0
        ):
            raise ValueError("Embedding deployment settings are incomplete.")
        if not text.strip() or len(text.encode("utf-8")) > input_limit:
            raise ValueError("Embedding input exceeds its configured bound.")
        # The LangChain adapter exposes vectors only. Without authoritative usage,
        # retain the reservation and disable automatic splitting into extra calls.
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
        if len(vector) != dimensions:
            raise ValueError("Unexpected embedding dimensions.")
        return vector
