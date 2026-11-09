import asyncio
import hashlib
import logging
from collections.abc import AsyncIterator
from contextlib import aclosing
from datetime import UTC, datetime
from typing import Literal
from uuid import UUID, uuid4

from psycopg import AsyncConnection, Error
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool, PoolTimeout
from pydantic import AwareDatetime, Field

from app.billing import BillingRejected
from app.chat_graph import ChatGraph, HistoryEntry
from app.preferences import read_preferences
from app.wire import ExecutionRejected, WireModel

logger = logging.getLogger(__name__)
ACTIVE = ("accepted", "running")


class SubmitExecution(WireModel):
    request_id: UUID
    question: str = Field(min_length=1, max_length=4000, pattern=r"\S")
    history: list[HistoryEntry] = Field(max_length=12)
    accept_before: AwareDatetime


class ExecutionError(WireModel):
    code: str
    message: str
    request_id: UUID | None = None


class Snapshot(WireModel):
    request_id: UUID
    execution_id: UUID
    status: Literal["accepted", "running", "completed", "failed", "interrupted"]
    text: str
    sources: list[str] = Field(default_factory=list)
    error: ExecutionError | None = None


class Delta(WireModel):
    offset: int
    text: str


class Executions:
    def __init__(
        self,
        pool: AsyncConnectionPool,
        graph: ChatGraph | None,
        *,
        concurrency: int,
        queue_limit: int,
    ) -> None:
        self.pool = pool
        self.graph = graph
        self.capacity = concurrency + queue_limit
        self.workers = asyncio.Semaphore(concurrency)
        self.submissions = asyncio.Lock()
        self.tasks: dict[UUID, asyncio.Task[None]] = {}

    async def recover(self) -> None:
        async with self.pool.connection() as connection:
            await connection.execute(
                """
                UPDATE execution SET status = 'interrupted', finished_at = now()
                WHERE status IN ('accepted', 'running')
                """
            )

    async def close(self) -> None:
        tasks = tuple(self.tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await self.recover()

    async def submit(self, request: SubmitExecution) -> Snapshot:
        payload = request.model_dump_json(exclude={"request_id", "accept_before"})
        input_hash = hashlib.sha256(payload.encode()).hexdigest()
        async with self.submissions:
            async with self.pool.connection() as connection:
                previous = await self.find(connection, request_id=request.request_id)
                if previous is not None:
                    snapshot, saved_hash = previous
                    if saved_hash != input_hash:
                        raise ExecutionRejected(
                            409, "REQUEST_CONFLICT", "Request ID is in use."
                        )
                    return snapshot
                if datetime.now(UTC) >= request.accept_before:
                    raise ExecutionRejected(
                        409, "ACCEPTANCE_EXPIRED", "Submission expired."
                    )
                if self.graph is None:
                    raise ExecutionRejected(
                        503, "MODEL_UNAVAILABLE", "Chat is unavailable."
                    )
                if len(self.tasks) >= self.capacity:
                    raise ExecutionRejected(429, "CAPACITY_EXHAUSTED", "Chat is busy.")
                execution_id = uuid4()
                preferences = await read_preferences(connection)
                await connection.execute(
                    """
                    INSERT INTO execution (execution_id, request_id, input_hash, status)
                    VALUES (%s, %s, %s, 'accepted')
                    """,
                    (execution_id, request.request_id, input_hash),
                )
                await connection.commit()
            task = asyncio.create_task(
                self.run(execution_id, request, self.graph, preferences.persona)
            )
            self.tasks[execution_id] = task
            task.add_done_callback(lambda _: self.tasks.pop(execution_id, None))
        return Snapshot(
            request_id=request.request_id,
            execution_id=execution_id,
            status="accepted",
            text="",
        )

    async def find(
        self,
        connection: AsyncConnection,
        *,
        execution_id: UUID | None = None,
        request_id: UUID | None = None,
    ) -> tuple[Snapshot, str] | None:
        async with connection.cursor(row_factory=dict_row) as cursor:
            await cursor.execute(
                "SELECT * FROM execution WHERE execution_id = %s OR request_id = %s",
                (execution_id, request_id),
            )
            row = await cursor.fetchone()
        if row is None:
            return None
        error = None
        if row["error_code"]:
            error = ExecutionError(
                code=row["error_code"],
                message="Chat could not finish.",
                request_id=row["request_id"],
            )
        return Snapshot(
            request_id=row["request_id"],
            execution_id=row["execution_id"],
            status=row["status"],
            text=row["text"],
            error=error,
        ), row["input_hash"]

    async def get(
        self,
        execution_id: UUID | None = None,
        *,
        request_id: UUID | None = None,
    ) -> Snapshot:
        async with self.pool.connection() as connection:
            result = await self.find(
                connection, execution_id=execution_id, request_id=request_id
            )
        if result is None:
            raise ExecutionRejected(404, "EXECUTION_NOT_FOUND", "Execution not found.")
        return result[0]

    async def finish(
        self, execution_id: UUID, status: str, error: str | None = None
    ) -> bool:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                UPDATE execution SET status = %s, error_code = %s, finished_at = now()
                WHERE execution_id = %s AND status IN ('accepted', 'running')
                """,
                (status, error, execution_id),
            )
            return cursor.rowcount == 1

    async def stop(self, execution_id: UUID) -> Snapshot:
        if await self.finish(execution_id, "interrupted"):
            task = self.tasks.get(execution_id)
            if task is not None:
                task.cancel()
        return await self.get(execution_id)

    async def run(
        self,
        execution_id: UUID,
        request: SubmitExecution,
        graph: ChatGraph,
        persona: str,
    ) -> None:
        try:
            async with self.workers:
                async with self.pool.connection() as connection:
                    cursor = await connection.execute(
                        """
                        UPDATE execution SET status = 'running'
                        WHERE execution_id = %s AND status = 'accepted'
                        """,
                        (execution_id,),
                    )
                    if cursor.rowcount != 1:
                        return
                async with aclosing(
                    graph.stream(
                        {
                            "execution_id": execution_id,
                            "question": request.question,
                            "history": request.history,
                            "persona": persona,
                        }
                    )
                ) as chunks:
                    async for text in chunks:
                        async with self.pool.connection() as connection:
                            saved = await connection.execute(
                                """
                                UPDATE execution SET text = text || %s
                                WHERE execution_id = %s AND status = 'running'
                                """,
                                (text, execution_id),
                            )
                            if saved.rowcount != 1:
                                return
                await self.finish(execution_id, "completed")
        except BillingRejected:
            await self.finish(execution_id, "failed", "MODEL_BUDGET_UNAVAILABLE")
        except Exception as error:  # noqa: BLE001 - sanitize all task failures
            logger.warning(
                "execution failed id=%s type=%s", execution_id, type(error).__name__
            )
            try:
                await self.finish(execution_id, "failed", "EXECUTION_FAILED")
            except (Error, PoolTimeout):
                logger.error("could not persist execution failure id=%s", execution_id)

    async def events(self, initial: Snapshot) -> AsyncIterator[str]:
        yield f"event: snapshot\ndata: {initial.model_dump_json()}\n\n"
        previous = initial.text
        snapshot = initial
        idle_polls = 0
        while snapshot.status in ACTIVE:
            await asyncio.sleep(0.25)
            snapshot = await self.get(initial.execution_id)
            if snapshot.text != previous:
                delta = Delta(
                    offset=len(previous.encode("utf-16-le")) // 2,
                    text=snapshot.text[len(previous) :],
                )
                yield f"event: delta\ndata: {delta.model_dump_json()}\n\n"
                previous = snapshot.text
                idle_polls = 0
            idle_polls += 1
            if idle_polls >= 40:
                yield ": keep-alive\n\n"
                idle_polls = 0
        yield f"event: terminal\ndata: {snapshot.model_dump_json()}\n\n"
