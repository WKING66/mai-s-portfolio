import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlalchemy import func, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlmodel import select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.api.errors import EXCEPTION_HANDLERS
from app.api.routes import router
from app.config import Settings
from app.contracts.model_catalog import RouteType
from app.contracts.wire import ExecutionRejected
from app.provider.model_routing import ModelRouting
from app.provider.provider_http import provider_http_client
from app.service.execution import Executions
from app.service.model_catalog import load_candidates
from app.service.prompt_catalog import active_persona
from app.storage.database import Sessions, create_database
from app.workflow.chat_graph import ConfiguredChat

logger = logging.getLogger(__name__)


async def monitor_worker(connection: AsyncConnection, executions: Executions) -> None:
    while True:
        try:
            # 锁和探活使用同一连接。失去会话后停止受理，不能用新连接假装仍持锁。
            async with asyncio.timeout(3):
                await connection.scalar(select(1))
                await connection.commit()
        except (SQLAlchemyError, TimeoutError) as error:
            executions.worker_available = False
            logger.error(
                "AI worker lost its database session type=%s invalidated=%s; restart required.",
                type(error).__name__,
                connection.invalidated,
            )
            await executions.close()
            return
        await asyncio.sleep(1)


async def clean_expired_answers(executions: Executions) -> None:
    # 清理在独立任务中运行，数据库扫描较慢时仍能及时检查执行锁。
    while executions.worker_available:
        try:
            await executions.expire_answers()
        except SQLAlchemyError:
            logger.warning("AI answer cleanup deferred.")
            await asyncio.sleep(60)
        else:
            await asyncio.sleep(3600)


def create_app(settings: Settings) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        engine = create_database(str(settings.ai_database_url), settings.ai_db_schema)
        sessions = Sessions(engine, class_=AsyncSession)
        provider_client = provider_http_client()
        routing = ModelRouting(provider_client)
        try:

            async def configured_chat(session: AsyncSession) -> tuple[ConfiguredChat, str] | None:
                candidates = await load_candidates(session, settings, RouteType.CHAT_REPLY)
                if not candidates:
                    return None
                if not routing.available(candidates):
                    raise ExecutionRejected(503, "MODEL_UNAVAILABLE", "模型服务暂不可用。")
                return ConfiguredChat(routing, candidates), await active_persona(session)

            executions = Executions(
                sessions,
                configured_chat,
                concurrency=settings.execution_concurrency,
                queue_limit=settings.execution_queue_limit,
                chat_timing_enabled=settings.chat_timing_enabled,
            )
            # 会话锁保证单执行进程，避免第二个进程启动时误把正在运行的问答标为中断。
            async with engine.connect() as connection:
                current = await connection.scalar(text("SELECT version_num FROM alembic_version"))
                if current is None or current < "0008":
                    raise RuntimeError("AI execution migration 0008 is required.")
                lock = await connection.scalar(
                    select(func.pg_try_advisory_lock(317341, 2))
                )
                # 已有执行进程时拒绝启动，保证其活动任务状态不被恢复流程覆盖。
                if not lock:
                    raise RuntimeError("An AI execution worker is already active.")
                await connection.commit()
                try:
                    await executions.recover()
                    app.state.executions = executions
                    app.state.sessions = sessions
                    monitor = asyncio.create_task(
                        monitor_worker(connection, executions)
                    )
                    cleanup = asyncio.create_task(clean_expired_answers(executions))
                    try:
                        yield
                    finally:
                        for task in (monitor, cleanup):
                            task.cancel()
                        await asyncio.gather(monitor, cleanup, return_exceptions=True)
                        await executions.close()
                finally:
                    if executions.worker_available and not connection.invalidated:
                        await connection.scalar(
                            select(func.pg_advisory_unlock(317341, 2))
                        )
        finally:
            try:
                await provider_client.aclose()
            finally:
                await engine.dispose()

    app = FastAPI(
        lifespan=lifespan,
        exception_handlers=EXCEPTION_HANDLERS,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.include_router(router)
    return app


def app_factory() -> FastAPI:
    return create_app(Settings())  # pyright: ignore[reportCallIssue]
