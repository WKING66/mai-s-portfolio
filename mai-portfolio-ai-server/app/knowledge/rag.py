import asyncio
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from time import monotonic
from typing import Literal
from uuid import UUID

from app.knowledge.client import Authorization, KnowledgeClient
from app.knowledge.store import Evidence, KnowledgeStore
from app.provider.model_routing import Candidate, ModelRouting

logger = logging.getLogger(__name__)
QuestionRoute = Literal["direct", "profile", "semantic"]


def route_question(question: str) -> QuestionRoute:
    normalized = re.sub(r"[\s?!？！。,.，]+", " ", question.casefold()).strip()
    if normalized in {
        "你好", "您好", "嗨", "谢谢", "在吗", "你是谁", "你是什么模型", "介绍一下你自己",
        "hi", "hello", "hey", "thanks", "who are you", "what model are you",
    }:
        return "direct"
    if re.search(
        r"(?:站长|作者|博主)(?:的)?(?:是谁|个人简介|简介|自我介绍|个人介绍|邮箱|联系方式|怎么联系|联系|github)"
        r"|(?:站长|作者|博主).{0,4}(?:是谁|简介|邮箱|联系方式|怎么联系|github)"
        r"|(?:介绍(?:一下|下)?|怎么联系|联系)(?:一下|下)?(?:站长|作者|博主)"
        r"|(?:who is (?:the )?(?:owner|author)(?! of\b)|contact (?:the )?(?:owner|author)\b)"
        r"|(?:owner|author)(?:'s)? (?:profile|bio|email|github)",
        normalized,
    ):
        return "profile"
    return "semantic"


async def retrieve_knowledge(
    store: KnowledgeStore, routing: ModelRouting, candidates: list[Candidate],
    route: QuestionRoute, question: str,
) -> list[Evidence]:
    if route == "profile":
        return await store.retrieve(profile=True)
    if route == "direct" or not candidates:
        return []
    live = await store.live_spaces()
    matched = [
        candidate for candidate, space in routing.embedding_options(candidates) if space.space_key in live
    ] if live else []
    if not matched:
        return []
    return await store.retrieve(query=await routing.embed(matched, question.strip()))


@dataclass(frozen=True, slots=True)
class VerifiedEvidence:
    evidence: Evidence
    title: str
    slug: str | None


async def validate_knowledge(client: KnowledgeClient, candidates: list[Evidence]) -> list[VerifiedEvidence]:
    if not candidates:
        return []
    sources = {item.source.key: item.source for item in candidates}
    authorized: dict[tuple[int, int, int], Authorization] = {}
    seen: set[tuple[int, int, int]] = set()
    for item in await client.validate(list(sources.values())):
        if item.key in seen:
            authorized.pop(item.key, None)
        elif item.authorized and item.current_change_seq == item.change_seq and item.title:
            authorized[item.key] = item
        seen.add(item.key)
    verified = []
    for item in candidates:
        auth = authorized.get(item.source.key)
        if auth is not None and auth.title is not None:
            verified.append(VerifiedEvidence(item, auth.title, auth.slug))
    return verified


def log_stage(request_id: UUID | None, stage: str, started: float, outcome: str) -> None:
    logger.info(
        "knowledge_stage requestId=%s stage=%s elapsedMs=%.1f outcome=%s",
        request_id, stage, (monotonic() - started) * 1000, outcome,
    )


@dataclass(frozen=True, slots=True)
class KnowledgeRag:
    store: KnowledgeStore
    client: KnowledgeClient
    candidates: Callable[[], Awaitable[list[Candidate]]]

    async def evidence(
        self, routing: ModelRouting, question: str, request_id: UUID | None,
    ) -> list[VerifiedEvidence]:
        route = route_question(question)
        started = monotonic()
        try:
            async with asyncio.timeout(2):
                candidates = await self.candidates() if route == "semantic" else []
                retrieved = await retrieve_knowledge(self.store, routing, candidates, route, question)
        except Exception as error:  # noqa: BLE001 - 检索故障时保留原聊天路径
            log_stage(request_id, "retrieve", started, type(error).__name__)
            return []
        log_stage(request_id, "retrieve", started, "found" if retrieved else "empty")
        if not retrieved:
            return []
        started = monotonic()
        try:
            verified = await validate_knowledge(self.client, retrieved)
        except Exception as error:  # noqa: BLE001 - 授权异常一律丢弃本轮候选
            log_stage(request_id, "validate", started, type(error).__name__)
            return []
        log_stage(request_id, "validate", started, "verified" if verified else "denied")
        return verified
