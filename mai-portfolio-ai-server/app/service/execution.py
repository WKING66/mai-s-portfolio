import asyncio
from collections.abc import Callable
from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime
from pydantic import Field as ValidationField
from sqlalchemy import CheckConstraint, DateTime, Text, func, update
from sqlmodel import Field, col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.contracts.wire import ExecutionRejected, WireModel
from app.service.preferences import ChatPreferences
from app.storage.database import Sessions
from app.workflow.chat_graph import ChatGraph, HistoryEntry

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
    def require_worker(self) -> None:
        if not self.worker_available:
            raise ExecutionRejected(
                503, "WORKER_UNAVAILABLE", "Chat worker is unavailable."
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
