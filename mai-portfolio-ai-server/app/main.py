import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from functools import lru_cache
from json import dumps, loads
from zoneinfo import ZoneInfo

from fastapi import FastAPI
from httpx import AsyncClient
from langchain_openai import ChatOpenAI
from sqlalchemy import func
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlmodel import select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.api.routes import register_routes
from app.config import (
    Settings,
    effective_settings,
)
from app.provider.model_gateway import ModelGateway
from app.provider.provider_http import provider_http_client
from app.service.billing import Billing, Price
from app.service.execution import (
    Executions,
)
from app.storage.database import Sessions, create_database
from app.workflow.chat_graph import ChatGraph

logger = logging.getLogger(__name__)


async def monitor_worker(connection: AsyncConnection, executions: Executions) -> None:
    while True:
        try:
            # 锁和探活使用同一连接。失去会话后停止受理，不能用新连接假装仍持锁。
            async with asyncio.timeout(3):
                await connection.scalar(select(1))
                await connection.commit()
        except (SQLAlchemyError, TimeoutError):
            executions.worker_available = False
            await executions.close()
            logger.error("AI worker lost its database session; restart required.")
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


def create_chat_graph(
    sessions: Sessions,
    settings: Settings,
    provider_client: AsyncClient,
) -> ChatGraph | None:
    if (
        settings.model_provider != "openai-compatible"
        or not settings.chat_base_url
        or not settings.chat_model
        or not settings.chat_api_key.get_secret_value()
    ):
        return None
    billing = Billing(
        sessions,
        budget=settings.ai_daily_budget,
        unlimited=settings.ai_daily_budget_unlimited,
        currency=settings.billing_currency,
        time_zone=ZoneInfo(settings.billing_time_zone),
        price_version=settings.price_version,
    )
    return ChatGraph(
        ModelGateway(billing),
        ChatOpenAI(
            model=settings.chat_model,
            base_url=settings.chat_base_url,
            api_key=settings.chat_api_key,
            max_retries=0,
            timeout=settings.model_timeout_seconds,
            use_responses_api=False,
            http_async_client=provider_client,
        ),
        price=Price(
            settings.chat_input_price_per_million,
            settings.chat_output_price_per_million,
        ),
        input_limit=settings.chat_billable_input_limit,
        output_limit=settings.chat_max_output_tokens,
    )


def create_app(settings: Settings) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        engine = create_database(str(settings.ai_database_url), settings.ai_db_schema)
        sessions = Sessions(engine, class_=AsyncSession)
        provider_client = provider_http_client()
        try:

            @lru_cache(maxsize=1)
            def build_graph(encoded: str) -> ChatGraph | None:
                return create_chat_graph(
                    sessions,
                    effective_settings(settings, loads(encoded)),
                    provider_client,
                )

            executions = Executions(
                sessions,
                lambda preferences: build_graph(
                    dumps(preferences.runtime, sort_keys=True)
                ),
                concurrency=settings.execution_concurrency,
                queue_limit=settings.execution_queue_limit,
            )
            # 会话锁保证单执行进程，避免第二个进程启动时误把正在运行的问答标为中断。
            async with engine.connect() as connection:
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

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    register_routes(app, settings)
    return app


def app_factory() -> FastAPI:
    return create_app(Settings())  # pyright: ignore[reportCallIssue]
