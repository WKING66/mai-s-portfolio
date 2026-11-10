import asyncio
import hashlib
import logging
from collections.abc import AsyncIterator, Callable
from contextlib import aclosing
from datetime import UTC, datetime, timedelta
from typing import Literal
from uuid import UUID, uuid4

from pydantic import AwareDatetime
from pydantic import Field as ValidationField
from sqlalchemy import CheckConstraint, DateTime, Text, func, update
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Field, col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.billing import BillingRejected
from app.chat_graph import ChatGraph, HistoryEntry
from app.database import Sessions
from app.preferences import ChatPreferences, read_preferences
from app.wire import ExecutionRejected, WireModel

logger = logging.getLogger(__name__)
ACTIVE = ("accepted", "running")


async def lock_submission(session: AsyncSession, request_id: UUID) -> None:
    # 截止后查无记录会触发网站释放个人额度，负查必须等待原受理事务提交或回滚。
    await session.exec(
        select(func.pg_advisory_xact_lock(317342, func.hashtext(str(request_id))))
    )


class SubmitExecution(WireModel):
    request_id: UUID
    question: str = ValidationField(min_length=1, max_length=4000, pattern=r"\S")
    history: list[HistoryEntry] = ValidationField(max_length=12)
    accept_before: AwareDatetime


class ExecutionError(WireModel):
    code: str
    message: str
    request_id: UUID | None = None


ExecutionStatus = Literal["accepted", "running", "completed", "failed", "interrupted"]


class Accepted(WireModel):
    execution_id: UUID = Field(primary_key=True)
    status: ExecutionStatus = Field(sa_type=Text)


class ExecutionFields(Accepted):
    request_id: UUID = Field(unique=True)
    text: str = Field(sa_type=Text, sa_column_kwargs={"server_default": ""})


class Execution(ExecutionFields, table=True):
    __tablename__ = "execution"  # pyright: ignore[reportAssignmentType]
    __table_args__ = (
        CheckConstraint(
            "status IN ('accepted', 'running', 'completed', 'failed', 'interrupted')"
        ),
    )

    input_hash: str = Field(sa_type=Text)
    error_code: str | None = Field(default=None, sa_type=Text)
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


class Delta(WireModel):
    offset: int
    text: str


class Executions:
    def __init__(
        self,
        sessions: Sessions,
        build_graph: Callable[[ChatPreferences], ChatGraph | None],
        *,
        concurrency: int,
        queue_limit: int,
    ) -> None:
        self.sessions = sessions
        self.worker_available = True
        self.build_graph = build_graph
        self.capacity = concurrency + queue_limit
        self.workers = asyncio.Semaphore(concurrency)
        self.submissions = asyncio.Lock()
        self.tasks: dict[UUID, asyncio.Task[None]] = {}

    # 重启只中断遗留执行并保留部分正文。上次请求可能已收费，不能自动重放。
    async def recover(self) -> None:
        async with self.sessions.begin() as session:
            await session.exec(
                update(Execution)
                .where(col(Execution.status).in_(ACTIVE))
                .values(status="interrupted", finished_at=func.now())
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
        async with self.sessions.begin() as session:
            await session.exec(
                update(Execution)
                .where(
                    col(Execution.finished_at) < datetime.now(UTC) - timedelta(days=1),
                    col(Execution.text) != "",
                )
                .values(text="")
            )

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
                preferences = await read_preferences(session)
                graph = self.build_graph(preferences)
                # 模型未配置时拒绝受理，避免给网站留下无法运行的队列记录。
                if graph is None:
                    raise ExecutionRejected(
                        503, "MODEL_UNAVAILABLE", "Chat is unavailable."
                    )
                # 容量耗尽时返回繁忙，避免无限积压已受理的请求。
                if len(self.tasks) >= self.capacity:
                    raise ExecutionRejected(429, "CAPACITY_EXHAUSTED", "Chat is busy.")
                execution_id = uuid4()
                # 3. 固定受理时的 Persona，之后的设置更新只影响新请求。
                persona = preferences.persona
                session.add(
                    Execution(
                        execution_id=execution_id,
                        request_id=request.request_id,
                        input_hash=input_hash,
                        status="accepted",
                        text="",
                    )
                )
            # 4. 受理记录提交后才调度。订阅必须始终能读到对应的持久记录。
            task = asyncio.create_task(self.run(execution_id, request, graph, persona))
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
        session: AsyncSession,
        *,
        execution_id: UUID | None = None,
        request_id: UUID | None = None,
    ) -> tuple[Snapshot, str] | None:
        condition = col(Execution.execution_id) == execution_id
        if request_id is not None:
            condition = col(Execution.request_id) == request_id
        row = (await session.exec(select(Execution).where(condition))).one_or_none()
        # 缺少记录由查询入口返回 404，查询路径始终不创建执行。
        if row is None:
            return None
        error = None
        # 只返回稳定错误码。供应商异常可能包含密钥或会话正文。
        if row.error_code:
            error = ExecutionError(
                code=row.error_code,
                message="Chat could not finish.",
                request_id=row.request_id,
            )
        return Snapshot.model_validate(
            row, from_attributes=True, update={"error": error}
        ), row.input_hash

    async def get(
        self,
        execution_id: UUID | None = None,
        *,
        request_id: UUID | None = None,
    ) -> Snapshot:
        self.require_worker()
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
                    and snapshot.execution_id not in self.tasks
                ):
                    recovered = await session.scalar(
                        update(Execution)
                        .where(
                            col(Execution.execution_id) == snapshot.execution_id,
                            col(Execution.status).in_(ACTIVE),
                        )
                        .values(status="interrupted", finished_at=func.now())
                        .returning(col(Execution.execution_id))
                    )
                    if recovered is not None:
                        snapshot.status = "interrupted"
                    else:
                        result = await self.find(
                            session, execution_id=snapshot.execution_id
                        )
        # 订阅只读取已受理执行，缺少记录时直接拒绝。
        if result is None:
            raise ExecutionRejected(404, "EXECUTION_NOT_FOUND", "Execution not found.")
        return result[0]

    # 只允许活动执行进入终态。迟到的停止或失败不能覆盖已有结果。
    async def finish(
        self, execution_id: UUID, status: ExecutionStatus, error: str | None = None
    ) -> bool:
        async with self.sessions.begin() as session:
            updated = await session.scalar(
                update(Execution)
                .where(
                    col(Execution.execution_id) == execution_id,
                    col(Execution.status).in_(ACTIVE),
                )
                .values(status=status, error_code=error, finished_at=func.now())
                .returning(col(Execution.execution_id))
            )
            return updated is not None

    async def stop(self, execution_id: UUID) -> Snapshot:
        self.require_worker()
        # 先提交中断，再尽力取消任务。未赢得终态竞争时返回已有快照。
        if await self.finish(execution_id, "interrupted"):
            task = self.tasks.get(execution_id)
            # 只有本进程仍持有任务时才能取消，持久化终态不依赖取消是否成功。
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
            # 1. 获得执行槽后才从已受理切换到运行中。
            async with self.workers:
                async with self.sessions.begin() as session:
                    started = await session.scalar(
                        update(Execution)
                        .where(
                            col(Execution.execution_id) == execution_id,
                            col(Execution.status) == "accepted",
                        )
                        .values(status="running")
                        .returning(col(Execution.execution_id))
                    )
                    # 排队期间已停止时跳过生成，避免中断后开始收费。
                    if started is None:
                        return
                # 2. 先保存正文增量，订阅者只读取已提交的数据。
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
                        async with self.sessions.begin() as session:
                            saved = await session.scalar(
                                update(Execution)
                                .where(
                                    col(Execution.execution_id) == execution_id,
                                    col(Execution.status) == "running",
                                )
                                .values(text=col(Execution.text) + text)
                                .returning(col(Execution.execution_id))
                            )
                            # 终态已被另一操作提交时停止消费，不再向终态正文追加内容。
                            if saved is None:
                                return
                # 3. 全部正文保存后再发布完成，终态快照因此包含最终正文。
                await self.finish(execution_id, "completed")
        except BillingRejected:
            await self.finish(execution_id, "failed", "MODEL_BUDGET_UNAVAILABLE")
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
                await self.finish(execution_id, "failed", "EXECUTION_FAILED")
            except SQLAlchemyError:
                logger.error("could not persist execution failure id=%s", execution_id)

    async def events(self, initial: Snapshot) -> AsyncIterator[str]:
        yield f"event: snapshot\ndata: {initial.model_dump_json()}\n\n"
        # 重连用持久快照对齐正文，之后只发送新增长度，不调度模型。
        previous = initial.text
        snapshot = initial
        idle_polls = 0
        while snapshot.status in ACTIVE:
            await asyncio.sleep(0.25)
            snapshot = await self.get(initial.execution_id)
            # 偏移按 UTF-16 计数，表情等字符也能与 Java 和浏览器的字符串索引对齐。
            if snapshot.text != previous:
                delta = Delta(
                    offset=len(previous.encode("utf-16-le")) // 2,
                    text=snapshot.text[len(previous) :],
                )
                yield f"event: delta\ndata: {delta.model_dump_json()}\n\n"
                previous = snapshot.text
                idle_polls = 0
            idle_polls += 1
            # 长时间无新正文时发送心跳，避免代理提前关闭订阅。
            if idle_polls >= 40:
                yield ": keep-alive\n\n"
                idle_polls = 0
        yield f"event: terminal\ndata: {snapshot.model_dump_json()}\n\n"
