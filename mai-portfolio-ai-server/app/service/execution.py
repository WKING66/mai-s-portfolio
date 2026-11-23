import asyncio
import hashlib
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import aclosing
from datetime import UTC, datetime, timedelta
from enum import IntEnum
from typing import Literal, cast
from uuid import UUID

from pydantic import AwareDatetime
from pydantic import Field as ValidationField
from sqlalchemy import BigInteger, DateTime, SmallInteger, Text, case, func, update
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Field, SQLModel, col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.chat_timing import ChatTiming
from app.contracts.wire import ExecutionRejected, WireModel
from app.storage.database import Sessions
from app.workflow.chat_graph import ConfiguredChat, HistoryEntry

logger = logging.getLogger(__name__)


class ExecutionCode(IntEnum):
    ACCEPTED = 0
    RUNNING = 1
    COMPLETED = 2
    FAILED = 3
    INTERRUPTED = 4


ACTIVE_CODES = (ExecutionCode.ACCEPTED, ExecutionCode.RUNNING)
ACTIVE = ("accepted", "running")


async def lock_submission(session: AsyncSession, request_id: UUID) -> None:
    # 截止后查无记录会触发网站释放个人额度，负查必须等待原受理事务提交或回滚。
    await session.exec(
        select(func.pg_advisory_xact_lock(317342, func.hashtext(str(request_id))))
    )


class SubmitExecution(WireModel):
    request_id: UUID
    question: str = ValidationField(min_length=1, max_length=300, pattern=r"\S")
    history: list[HistoryEntry] = ValidationField(max_length=10)
    accept_before: AwareDatetime


class ExecutionError(WireModel):
    code: str
    message: str
    request_id: UUID | None = None


ExecutionStatus = Literal["accepted", "running", "completed", "failed", "interrupted"]


class Accepted(WireModel):
    execution_id: str
    status: ExecutionStatus


class ExecutionFields(Accepted):
    request_id: UUID = Field(unique=True)
    text: str = Field(sa_type=Text, sa_column_kwargs={"server_default": ""})


class Execution(SQLModel, table=True):
    __tablename__ = "model_execution"  # pyright: ignore[reportAssignmentType]
    id: int | None = Field(default=None, primary_key=True, sa_type=BigInteger)
    request_id: UUID = Field(unique=True)
    status: int = Field(sa_type=SmallInteger)
    input_hash: str = Field(sa_type=Text)
    text: str = Field(default="", sa_type=Text)
    error: str | None = Field(default=None, sa_type=Text)
    input_tokens: int | None = Field(default=None, sa_type=BigInteger)
    output_tokens: int | None = Field(default=None, sa_type=BigInteger)
    usage_status: int = Field(default=0, sa_type=SmallInteger)
    created_at: datetime | None = Field(
        default=None,
        nullable=False,
        sa_type=DateTime(timezone=True),
        sa_column_kwargs={"server_default": func.now()},
    )
    finished_at: datetime | None = Field(default=None, sa_type=DateTime(timezone=True))


class Snapshot(ExecutionFields):
    sources: list[str] = Field(default_factory=list)
    error: ExecutionError | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    usage_status: int = 0


class Delta(WireModel):
    offset: int
    text: str


ProgressPhase = Literal["queued", "preparing", "thinking"]


class Progress(WireModel):
    phase: ProgressPhase


class Executions:
    def __init__(
        self,
        sessions: Sessions,
        build_configured: Callable[[AsyncSession], Awaitable[tuple[ConfiguredChat, str] | None]],
        *,
        concurrency: int,
        queue_limit: int,
        chat_timing_enabled: bool = False,
    ) -> None:
        self.sessions = sessions
        self.worker_available = True
        self.build_configured = build_configured
        self.capacity = concurrency + queue_limit
        self.chat_timing_enabled = chat_timing_enabled
        self.workers = asyncio.Semaphore(concurrency)
        self.submissions = asyncio.Lock()
        self.tasks: dict[int, asyncio.Task[None]] = {}
        self.thinking: set[int] = set()

    # 重启只中断遗留执行并保留部分正文。上次请求可能已收费，不能自动重放。
    async def recover(self) -> None:
        async with self.sessions.begin() as session:
            await session.exec(
                update(Execution)
                .where(col(Execution.status).in_(ACTIVE_CODES))
                .values(status=ExecutionCode.INTERRUPTED, usage_status=3, finished_at=func.now())
            )

    async def close(self) -> None:
        tasks = tuple(self.tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self.worker_available:
            await self.recover()

    async def expire_answers(self) -> None:
        # AI 只保存短期可恢复正文。请求标识和账本独立保留，避免清理后重复收费。
        cutoff = datetime.now(UTC) - timedelta(days=1)
        batch_size = 500
        for _ in range(20):
            async with self.sessions.begin() as session:
                ids = (
                    select(col(Execution.id))
                    .where(
                        col(Execution.finished_at) < cutoff, col(Execution.text) != ""
                    )
                    .order_by(col(Execution.finished_at), col(Execution.id))
                    .limit(batch_size)
                    .with_for_update(skip_locked=True)
                )
                cleared = await session.exec(
                    update(Execution)
                    .where(col(Execution.id).in_(ids))
                    .values(
                        text="",
                        error=case(
                            (col(Execution.status) == ExecutionCode.COMPLETED, "EXECUTION_TEXT_EXPIRED"),
                            else_=col(Execution.error),
                        ),
                    )
                    .returning(col(Execution.id))
                )
                count = len(cleared.scalars().all())
            if count < batch_size:
                return

    def require_worker(self) -> None:
        if not self.worker_available:
            raise ExecutionRejected(
                503, "WORKER_UNAVAILABLE", "Chat worker is unavailable."
            )

    async def submit(self, request: SubmitExecution) -> Snapshot:
        # 1. 去重比较问题和历史。截止时间可随重试更新，业务输入不能变。
        payload = request.model_dump_json(exclude={"request_id", "accept_before"})
        input_hash = hashlib.sha256(payload.encode()).hexdigest()
        async with self.submissions:
            self.require_worker()
            async with self.sessions.begin() as session:
                await lock_submission(session, request.request_id)
                previous = await self.find(session, request_id=request.request_id)
                # 先查已受理记录，确保截止后的合法重试仍能找到原执行。
                if previous is not None:
                    snapshot, saved_hash = previous
                    # 同一标识绑定一份输入。问题或历史变更时客户端须使用新标识。
                    if saved_hash != input_hash:
                        raise ExecutionRejected(
                            409, "REQUEST_CONFLICT", "Request ID is in use."
                        )
                    return snapshot
                # 2. 截止后禁止首次受理，网站才能确认丢失回包的旧请求不会再开始。
                if datetime.now(UTC) >= request.accept_before:
                    raise ExecutionRejected(
                        409, "ACCEPTANCE_EXPIRED", "Submission expired."
                    )
                configured = await self.build_configured(session)
                # 模型未配置时拒绝受理，避免给网站留下无法运行的队列记录。
                if configured is None:
                    raise ExecutionRejected(
                        503, "MODEL_UNAVAILABLE", "Chat is unavailable."
                    )
                # 容量耗尽时返回繁忙，避免无限积压已受理的请求。
                if len(self.tasks) >= self.capacity:
                    raise ExecutionRejected(429, "CAPACITY_EXHAUSTED", "Chat is busy.")
                graph, persona = configured
                # 3. 固定受理时的 Persona，之后的设置更新只影响新请求。
                execution = Execution(
                    request_id=request.request_id,
                    input_hash=input_hash,
                    status=ExecutionCode.ACCEPTED,
                )
                session.add(execution)
                await session.flush()
                assert execution.id is not None
                execution_id = execution.id
            # 4. 受理记录提交后才调度。订阅必须始终能读到对应的持久记录。
            timing = ChatTiming(request.request_id, execution_id) if self.chat_timing_enabled else None
            task = asyncio.create_task(self.run(execution_id, request, graph, persona, timing))
            self.tasks[execution_id] = task

            def on_done(finished: asyncio.Task[None]) -> None:
                self.tasks.pop(execution_id, None)
                if timing is not None:
                    timing.log_finished(finished.cancelled())

            task.add_done_callback(on_done)
        return Snapshot(
            request_id=request.request_id,
            execution_id=str(execution_id),
            status="accepted",
            text="",
        )

    async def find(
        self,
        session: AsyncSession,
        *,
        execution_id: int | None = None,
        request_id: UUID | None = None,
    ) -> tuple[Snapshot, str] | None:
        condition = col(Execution.id) == execution_id
        if request_id is not None:
            condition = col(Execution.request_id) == request_id
        row = (await session.exec(select(Execution).where(condition))).one_or_none()
        # 缺少记录由查询入口返回 404，查询路径始终不创建执行。
        if row is None:
            return None
        error = None
        # 只返回稳定错误码。供应商异常可能包含密钥或会话正文。
        if row.error:
            error = ExecutionError(
                code=row.error,
                message="Chat could not finish.",
                request_id=row.request_id,
            )
        return Snapshot(
            execution_id=str(row.id),
            request_id=row.request_id,
            status=cast(ExecutionStatus, ExecutionCode(row.status).name.lower()),
            text=row.text,
            error=error,
            input_tokens=row.input_tokens,
            output_tokens=row.output_tokens,
            usage_status=row.usage_status,
        ), row.input_hash

    async def get(
        self,
        execution_id: int | None = None,
        *,
        request_id: UUID | None = None,
    ) -> Snapshot:
        self.require_worker()
        if request_id is None:
            # SSE 按执行编号读取已提交快照。只有活动记录找不到本地任务时才与受理互斥复查。
            async with self.sessions() as session:
                result = await self.find(session, execution_id=execution_id)
            if result is None:
                raise ExecutionRejected(404, "EXECUTION_NOT_FOUND", "Execution not found.")
            snapshot, _ = result
            if snapshot.status not in ACTIVE or int(snapshot.execution_id) in self.tasks:
                return snapshot
        async with self.submissions, self.sessions.begin() as session:
            self.require_worker()
            if request_id is not None:
                await lock_submission(session, request_id)
            result = await self.find(
                session, execution_id=execution_id, request_id=request_id
            )
            # 短暂断库可能让终态保存失败。任务退出后，重连可原子恢复这条记录。
            if result is not None:
                snapshot, _ = result
                if (
                    snapshot.status in ACTIVE
                    and int(snapshot.execution_id) not in self.tasks
                ):
                    recovered = await session.scalar(
                        update(Execution)
                        .where(
                            col(Execution.id) == int(snapshot.execution_id),
                            col(Execution.status).in_(ACTIVE_CODES),
                        )
                        .values(status=ExecutionCode.INTERRUPTED, usage_status=3, finished_at=func.now())
                        .returning(col(Execution.id))
                    )
                    if recovered is not None:
                        snapshot.status = "interrupted"
                        snapshot.usage_status = 3
                    else:
                        result = await self.find(
                            session, execution_id=int(snapshot.execution_id)
                        )
        # 订阅只读取已受理执行，缺少记录时直接拒绝。
        if result is None:
            raise ExecutionRejected(404, "EXECUTION_NOT_FOUND", "Execution not found.")
        return result[0]

    # 只允许活动执行进入终态。迟到的停止或失败不能覆盖已有结果。
    async def finish(
        self, execution_id: int, status: ExecutionCode, error: str | None = None,
        graph: ConfiguredChat | None = None,
    ) -> bool:
        async with self.sessions.begin() as session:
            updated = await session.scalar(
                update(Execution)
                .where(
                    col(Execution.id) == execution_id,
                    col(Execution.status).in_(ACTIVE_CODES),
                )
                .values(
                    status=status,
                    error=error,
                    input_tokens=graph.input_tokens if graph else None,
                    output_tokens=graph.output_tokens if graph else None,
                    usage_status=graph.usage_status if graph else 3,
                    finished_at=func.now(),
                )
                .returning(col(Execution.id))
            )
            return updated is not None

    async def stop(self, execution_id: int) -> Snapshot:
        self.require_worker()
        # 先提交中断，再尽力取消任务。未赢得终态竞争时返回已有快照。
        if await self.finish(execution_id, ExecutionCode.INTERRUPTED):
            task = self.tasks.get(execution_id)
            # 只有本进程仍持有任务时才能取消，持久化终态不依赖取消是否成功。
            if task is not None:
                task.cancel()
        return await self.get(execution_id)

    async def run(
        self,
        execution_id: int,
        request: SubmitExecution,
        graph: ConfiguredChat,
        persona: str,
        timing: ChatTiming | None = None,
    ) -> None:
        try:
            # 1. 获得执行槽后才从已受理切换到运行中。
            async with self.workers:
                if timing is not None:
                    timing.slot_acquired_ms = timing.elapsed_ms()
                async with self.sessions.begin() as session:
                    started = await session.scalar(
                        update(Execution)
                        .where(
                            col(Execution.id) == execution_id,
                            col(Execution.status) == ExecutionCode.ACCEPTED,
                        )
                        .values(status=ExecutionCode.RUNNING)
                        .returning(col(Execution.id))
                    )
                    # 排队期间已停止时跳过生成，避免中断后开始收费。
                    if started is None:
                        return
                if timing is not None:
                    timing.running_committed_ms = timing.elapsed_ms()
                # 2. 先保存正文增量，订阅者只读取已提交的数据。
                async with aclosing(
                    graph.stream(
                        {
                            "execution_id": execution_id,
                            "question": request.question,
                            "history": request.history,
                            "persona": persona,
                        },
                        on_thinking=lambda: self.thinking.add(execution_id),
                        timing=timing,
                    )
                ) as chunks:
                    async for text in chunks:
                        async with self.sessions.begin() as session:
                            saved = await session.scalar(
                                update(Execution)
                                .where(
                                    col(Execution.id) == execution_id,
                                    col(Execution.status) == ExecutionCode.RUNNING,
                                )
                                .values(text=col(Execution.text) + text)
                                .returning(col(Execution.id))
                            )
                            # 终态已被另一操作提交时停止消费，不再向终态正文追加内容。
                            if saved is None:
                                return
                        if timing is not None and timing.first_body_committed_ms is None and text.strip():
                            timing.first_body_committed_ms = timing.elapsed_ms()
                # 3. 全部正文保存后再发布完成，终态快照因此包含最终正文。
                await self.finish(execution_id, ExecutionCode.COMPLETED, graph=graph)
        except ExecutionRejected as error:
            await self.finish(execution_id, ExecutionCode.FAILED, error.code, graph)
        except Exception as error:  # noqa: BLE001 - 任务边界统一脱敏
            # 只记录异常类型链，区分 DNS 超时和传输失败，避免输出密钥、请求体或供应商响应。
            cause = error.__cause__
            causes: list[str] = []
            while cause is not None and len(causes) < 4:
                causes.append(type(cause).__name__)
                cause = cause.__cause__
            logger.warning(
                "execution failed id=%s type=%s causes=%s",
                execution_id,
                type(error).__name__,
                " -> ".join(causes) or "-",
            )
            try:
                await self.finish(execution_id, ExecutionCode.FAILED, "EXECUTION_FAILED", graph)
            except SQLAlchemyError:
                logger.error("could not persist execution failure id=%s", execution_id)
        finally:
            self.thinking.discard(execution_id)

    def progress_phase(self, snapshot: Snapshot) -> ProgressPhase:
        if snapshot.status == "accepted":
            return "queued"
        if int(snapshot.execution_id) in self.thinking:
            return "thinking"
        return "preparing"

    async def events(
        self, initial: Snapshot
    ) -> AsyncIterator[tuple[Literal["snapshot", "progress", "delta", "terminal"], Snapshot | Progress | Delta]]:
        yield "snapshot", initial
        # 复用订阅轮询，只在正文到达前发送变化的阶段。重连会看到当前阶段。
        previous = initial.text
        phase: ProgressPhase | None = None
        snapshot = initial
        execution_id = int(initial.execution_id)
        while snapshot.status in ACTIVE:
            if not snapshot.text and phase != 'thinking':
                observed = self.progress_phase(snapshot)
                if observed != phase:
                    yield "progress", Progress(phase=observed)
                    phase = observed
            await asyncio.sleep(0.25)
            snapshot = await self.get(execution_id)
            # 偏移按 UTF-16 计数，表情等字符也能与 Java 和浏览器的字符串索引对齐。
            if snapshot.text != previous:
                delta = Delta(
                    offset=len(previous.encode("utf-16-le")) // 2,
                    text=snapshot.text[len(previous) :],
                )
                yield "delta", delta
                previous = snapshot.text
        yield "terminal", snapshot
