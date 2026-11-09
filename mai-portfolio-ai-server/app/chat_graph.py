from collections.abc import AsyncGenerator
from contextlib import aclosing
from typing import Any, Literal, TypedDict, cast
from uuid import UUID

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, ConfigDict, Field

from app.billing import Price
from app.model_gateway import ModelGateway


class HistoryEntry(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["user", "assistant"]
    text: str = Field(min_length=1, max_length=4000)


class ChatState(TypedDict):
    execution_id: UUID
    question: str
    history: list[HistoryEntry]
    persona: str


class ChatGraph:
    def __init__(
        self,
        gateway: ModelGateway,
        model: ChatOpenAI,
        *,
        price: Price,
        input_limit: int,
        output_limit: int,
    ) -> None:
        self.gateway = gateway
        self.model = model
        self.price = price
        self.input_limit = input_limit
        self.output_limit = output_limit
        self.graph = (
            StateGraph(ChatState)
            .add_node("generate", self.generate)
            .add_edge(START, "generate")
            .add_edge("generate", END)
            .compile()
        )

    async def generate(self, state: ChatState) -> None:
        messages: list[BaseMessage] = [
            SystemMessage(
                "You are the site's AI guide. Use history only to interpret follow-up questions. "
                "Do not present unverified claims as facts about the site owner. "
                "Say when you lack evidence. Style: " + state["persona"]
            )
        ]
        for entry in state["history"]:
            message = (
                HumanMessage(entry.text)
                if entry.role == "user"
                else AIMessage(entry.text)
            )
            messages.append(message)
        messages.append(HumanMessage(state["question"]))
        writer = get_stream_writer()
        async with aclosing(
            self.gateway.stream_chat(
                state["execution_id"],
                self.model,
                messages,
                price=self.price,
                input_limit=self.input_limit,
                output_limit=self.output_limit,
            )
        ) as chunks:
            async for text in chunks:
                writer(text)

    async def stream(self, state: ChatState) -> AsyncGenerator[str]:
        async with aclosing(
            cast(
                AsyncGenerator[Any],
                self.graph.astream(state, stream_mode="custom", version="v2"),
            )
        ) as parts:
            async for part in parts:
                if part["type"] == "custom" and isinstance(part["data"], str):
                    yield part["data"]
