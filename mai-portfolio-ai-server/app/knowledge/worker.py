import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from itertools import batched
from typing import Literal
from uuid import uuid4

import httpx
from pgvector import Vector
from pydantic import ValidationError

from app.contracts.wire import ExecutionRejected
from app.knowledge.client import Claim, KnowledgeClient
from app.knowledge.projection import (
    Chunk,
    ContentHashCollision,
    KnowledgeValidationError,
    projection_chunks,
    projection_hash,
)
from app.knowledge.store import KnowledgeStore, PreparedChunk, StaleSequenceError
from app.provider.embedding import InvalidEmbedding
from app.provider.model_routing import Candidate, ModelRouting

logger = logging.getLogger(__name__)
RENEW_INTERVAL = 40.0
RENEW_RETRY_INTERVAL = 5.0
RENEW_SAFETY_MARGIN = 15.0
BATCH_SIZE = 12
YIELD_INTERVAL = 0.25


class LeaseLost(Exception):
    pass


class LeaseRenewFailed(Exception):
    pass


class KnowledgeWorker:
    def __init__(
        self, client: KnowledgeClient, store: KnowledgeStore, routing: ModelRouting,
        candidates: Callable[[], Awaitable[list[Candidate]]],
        active_chats: Callable[[], int], *, threshold: int, poll_seconds: float,
    ) -> None:
        self.client = client
        self.store = store
        self.routing = routing
        self.candidates = candidates
        self.active_chats = active_chats
        self.threshold = threshold
        self.poll_seconds = poll_seconds
        self.worker_id = f"ai-{uuid4().hex}"

    async def run(self) -> None:
        while True:
            try:
                claim = await self.client.claim(self.worker_id)
                if claim is not None:
                    await self.handle(claim)
                    continue
            except Exception as error:  # noqa: BLE001 - 单次领用失败不终止后台轮询
                logger.warning("knowledge_sync_poll_failed type=%s", type(error).__name__)
            await asyncio.sleep(self.poll_seconds)

    async def handle(self, claim: Claim) -> None:
        try:
            build = asyncio.create_task(self.apply(claim))
            heartbeat = asyncio.create_task(self.renew(claim))
            try:
                done, _ = await asyncio.wait((build, heartbeat), return_when=asyncio.FIRST_COMPLETED)
                if heartbeat in done:
                    heartbeat.result()
                count = build.result()
            finally:
                for task in (build, heartbeat):
                    if not task.done():
                        task.cancel()
                await asyncio.gather(build, heartbeat, return_exceptions=True)
        except LeaseLost:
            logger.warning("knowledge_sync_lease_lost queue_id=%s", claim.queue_id)
            return
        except StaleSequenceError:
            logger.warning(
                "knowledge_sync_stale_sequence queue_id=%s change_seq=%s",
                claim.queue_id, claim.change_seq,
            )
            return
        except Exception as error:  # noqa: BLE001 - 以固定枚举上报任务失败
            if isinstance(error, (KnowledgeValidationError, InvalidEmbedding, UnicodeError)):
                kind = 3
            elif isinstance(error, LeaseRenewFailed):
                kind = 4
            elif isinstance(error, ExecutionRejected):
                kind = 2
            else:
                kind = 1
            logger.warning(
                "knowledge_sync_failed queue_id=%s error_kind=%s type=%s",
                claim.queue_id, kind, type(error).__name__,
            )
            await self._report(claim, "fail", errorKind=kind, permanent=kind == 3)
            return
        # 提交失败由 Java 租约重领，已提交的本地版本可幂等 ACK。
        await self._report(claim, "complete", chunkCount=count)

    async def _report(
        self, claim: Claim, operation: Literal["complete", "fail"], **fields: int | bool,
    ) -> None:
        try:
            await self.client.update(claim, operation, **fields)
        except httpx.HTTPStatusError as error:
            if error.response.status_code == 409:
                logger.warning(
                    "knowledge_sync_lease_lost queue_id=%s operation=%s",
                    claim.queue_id, operation,
                )
                return
            raise

    async def renew(self, claim: Claim) -> None:
        lease_until = claim.lease_until
        delay = RENEW_INTERVAL
        while True:
            await asyncio.sleep(delay)
            if (lease_until - datetime.now(UTC)).total_seconds() <= RENEW_SAFETY_MARGIN:
                raise LeaseRenewFailed
            try:
                lease_until = await self.client.renew(claim)
            except httpx.HTTPStatusError as error:
                status = error.response.status_code
                if status == 409:
                    raise LeaseLost from None
                if status != 429 and status < 500:
                    raise LeaseRenewFailed from None
                delay = self._next_renew_retry(lease_until)
                logger.warning("knowledge_sync_renew_retry queue_id=%s status=%s", claim.queue_id, status)
            except (httpx.HTTPError, TimeoutError, ValidationError) as error:
                delay = self._next_renew_retry(lease_until)
                logger.warning(
                    "knowledge_sync_renew_retry queue_id=%s type=%s",
                    claim.queue_id, type(error).__name__,
                )
            else:
                delay = RENEW_INTERVAL

    @staticmethod
    def _next_renew_retry(lease_until: datetime) -> float:
        retry_delay = min(RENEW_RETRY_INTERVAL, RENEW_INTERVAL)
        remaining = (lease_until - datetime.now(UTC)).total_seconds()
        if remaining - retry_delay <= RENEW_SAFETY_MARGIN:
            raise LeaseRenewFailed
        return retry_delay

    async def yield_to_chat(self) -> None:
        while self.active_chats() >= self.threshold:
            await asyncio.sleep(YIELD_INTERVAL)

    async def apply(self, claim: Claim) -> int:
        if claim.action != 1:
            return await self.store.apply(claim)
        projection = claim.projection
        if projection is None or projection_hash(projection) != claim.projection_hash:
            raise KnowledgeValidationError("Knowledge projection hash mismatch.")
        chunks = projection_chunks(claim.source_type, claim.source_id, projection)
        unique: dict[bytes, Chunk] = {}
        for chunk in chunks:
            previous = unique.setdefault(chunk.content_hash, chunk)
            if previous.text.encode("utf-8") != chunk.text.encode("utf-8"):
                raise ContentHashCollision("Knowledge content hash collision.")

        committed = await self.store.committed_count(claim, chunks)
        if committed is not None:
            return committed

        await self.yield_to_chat()
        options = self.routing.embedding_options(await self.candidates())
        if not options:
            raise ExecutionRejected(503, "EMBEDDING_UNAVAILABLE", "Embedding is unavailable.")
        candidate, space = options[0]
        vectors: dict[bytes, Vector] = {}
        for hashes in batched(unique, BATCH_SIZE):
            await self.yield_to_chat()
            batch = {digest: unique[digest] for digest in hashes}
            vectors.update(await self.store.reusable(claim, space, batch))
            missing = [digest for digest in hashes if digest not in vectors]
            if missing:
                results = await self.routing.embed_batch([candidate], [unique[key].text for key in missing])
                for digest, result in zip(missing, results, strict=True):
                    if result.space != space:
                        raise InvalidEmbedding("Embedding space changed during knowledge build.")
                    vectors[digest] = result.vector
        await self.yield_to_chat()
        return await self.store.apply(
            claim, space, [PreparedChunk(chunk, vectors[chunk.content_hash]) for chunk in chunks],
        )
