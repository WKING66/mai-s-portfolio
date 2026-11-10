import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress
from functools import lru_cache
from json import dumps, loads
from secrets import compare_digest
from typing import Annotated
from uuid import UUID
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from langchain_openai import ChatOpenAI
from sqlalchemy import func
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlmodel import col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.billing import Billing, ModelAttempt, Price
from app.chat_graph import ChatGraph
from app.config import (
    EDITABLE_SETTINGS,
    Settings,
    effective_settings,
    encrypt_provider_key,
)
from app.database import Sessions, create_database
from app.execution import (
    Accepted,
    Execution,
    ExecutionError,
    Executions,
    Snapshot,
    SubmitExecution,
)
from app.model_gateway import ModelGateway
from app.preferences import (
    ChatPreferences,
    RuntimeValue,
    SettingsView,
    UpdatePreferences,
    read_preferences,
    update_preferences,
)
from app.wire import ExecutionRejected

logger = logging.getLogger(__name__)
bearer = HTTPBearer(auto_error=False)


async def monitor_worker(connection: AsyncConnection, executions: Executions) -> None:
    next_cleanup = 0.0
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
        now = asyncio.get_running_loop().time()
        if now >= next_cleanup:
            try:
                await executions.expire_answers()
                next_cleanup = now + 3600
            except SQLAlchemyError:
                logger.warning("AI answer cleanup deferred.")
        await asyncio.sleep(1)


def create_chat_graph(sessions: Sessions, settings: Settings) -> ChatGraph | None:
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
        currency=settings.billing_currency,
        time_zone=ZoneInfo(settings.billing_time_zone),
        price_version=settings.price_version,
        enabled=settings.ai_paid_calls_enabled,
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
        ),
        price=Price(
            settings.chat_input_price_per_million,
            settings.chat_output_price_per_million,
        ),
        input_limit=settings.chat_billable_input_limit,
        output_limit=settings.chat_max_output_tokens,
    )


def create_app(settings: Settings) -> FastAPI:
    execution_token = settings.ai_execution_token.get_secret_value().encode()
    management_token = settings.ai_management_token.get_secret_value().encode()
    # 两种凭证必须隔离，避免持有执行权限的调用方同时获得配置权限。
    if compare_digest(execution_token, management_token):
        raise ValueError("Execution and management credentials must differ.")

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        engine = create_database(str(settings.ai_database_url), settings.ai_db_schema)
        sessions = Sessions(engine, class_=AsyncSession)
        try:

            @lru_cache(maxsize=1)
            def build_graph(encoded: str) -> ChatGraph | None:
                return create_chat_graph(
                    sessions, effective_settings(settings, loads(encoded))
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
                    try:
                        yield
                    finally:
                        monitor.cancel()
                        with suppress(asyncio.CancelledError):
                            await monitor
                        await executions.close()
                finally:
                    if executions.worker_available and not connection.invalidated:
                        await connection.scalar(
                            select(func.pg_advisory_unlock(317341, 2))
                        )
        finally:
            await engine.dispose()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    async def require_execution(
        credential: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    ) -> None:
        # 只允许持有当前用途服务凭证的后端访问，浏览器通过网站中转。
        if credential is None or not compare_digest(
            credential.credentials.encode(), execution_token
        ):
            raise ExecutionRejected(
                401, "UNAUTHORIZED", "Invalid execution credential."
            )

    async def require_management(
        credential: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    ) -> None:
        # 只允许持有当前用途服务凭证的后端访问，浏览器通过网站中转。
        if credential is None or not compare_digest(
            credential.credentials.encode(), management_token
        ):
            raise ExecutionRejected(
                401, "UNAUTHORIZED", "Invalid management credential."
            )

    def settings_view(preferences: ChatPreferences) -> SettingsView:
        effective = effective_settings(settings, preferences.runtime)
        return SettingsView(
            revision=preferences.revision,
            persona=preferences.persona,
            model_provider=effective.model_provider,
            chat_model=effective.chat_model,
            chat_max_output_tokens=effective.chat_max_output_tokens,
            embedding_model=effective.embedding_model,
            embedding_dimensions=effective.embedding_dimensions,
            chat_key_configured=bool(effective.chat_api_key.get_secret_value()),
            embedding_key_configured=bool(
                effective.embedding_api_key.get_secret_value()
            ),
            runtime=effective.model_dump(mode="json", include=set(EDITABLE_SETTINGS)),
        )

    @app.get("/internal/v1/settings", dependencies=[Depends(require_management)])
    async def get_settings(request: Request) -> SettingsView:
        sessions: Sessions = request.app.state.sessions
        async with sessions() as session:
            return settings_view(await read_preferences(session))

    @app.put("/internal/v1/settings", dependencies=[Depends(require_management)])
    async def put_settings(request: Request, body: UpdatePreferences) -> SettingsView:
        sessions: Sessions = request.app.state.sessions
        executions = execution_service(request)
        executions.require_worker()
        async with executions.submissions, sessions.begin() as session:
            current = await read_preferences(session)
            supplied = body.runtime or {}
            if supplied.keys() - EDITABLE_SETTINGS:
                raise ExecutionRejected(
                    422, "INVALID_SETTINGS", "Unknown runtime setting."
                )
            runtime: dict[str, RuntimeValue] = current.runtime | supplied
            try:
                for field in ("chat_api_key", "embedding_api_key"):
                    key = getattr(body, field)
                    if key is not None:
                        if not 1 <= len(key.get_secret_value().strip()) <= 2048:
                            raise ValueError("Invalid provider key length.")
                        runtime[field] = encrypt_provider_key(settings, key)
                effective_settings(settings, runtime)
            except ValueError as error:
                raise ExecutionRejected(
                    422, "INVALID_SETTINGS", "Invalid runtime settings."
                ) from error
            # 避免降低预算后，旧队列仍按旧配置开始收费。Persona 单独更新不影响已有请求。
            if runtime != current.runtime and executions.tasks:
                raise ExecutionRejected(
                    409, "EXECUTIONS_ACTIVE", "Wait for current executions to finish."
                )
            return settings_view(await update_preferences(session, body, runtime))

    def execution_service(request: Request) -> Executions:
        service: Executions = request.app.state.executions
        return service

    def error_response(status: int, code: str, message: str) -> JSONResponse:
        error = ExecutionError(code=code, message=message)
        return JSONResponse(error.model_dump(mode="json"), status_code=status)

    @app.exception_handler(ExecutionRejected)
    async def rejected(_: Request, error: ExecutionRejected) -> JSONResponse:
        return error_response(error.status, error.code, error.message)

    @app.exception_handler(RequestValidationError)
    async def invalid(_: Request, error: RequestValidationError) -> JSONResponse:
        return error_response(422, "INVALID_INPUT", "Invalid request.")

    @app.exception_handler(SQLAlchemyError)
    async def unavailable(_: Request, error: Exception) -> JSONResponse:
        logger.warning("AI database unavailable type=%s", type(error).__name__)
        return error_response(
            503, "DEPENDENCY_UNAVAILABLE", "Chat storage is unavailable."
        )

    @app.get("/internal/v1/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/internal/v1/health/ready")
    async def ready(request: Request) -> dict[str, str]:
        execution_service(request).require_worker()
        sessions: Sessions = request.app.state.sessions
        async with sessions() as session:
            await session.exec(select(col(Execution.execution_id)).limit(1))
            await session.exec(select(col(ModelAttempt.attempt_id)).limit(1))
            preferences = await read_preferences(session)
            try:
                effective_settings(settings, preferences.runtime)
            except ValueError as error:
                raise ExecutionRejected(
                    503, "INVALID_STORED_SETTINGS", "Stored settings are unavailable."
                ) from error
        return {"status": "ready"}

    @app.post(
        "/internal/v1/executions",
        status_code=202,
        dependencies=[Depends(require_execution)],
    )
    async def submit(
        request: Request,
        body: SubmitExecution,
    ) -> Accepted:
        snapshot = await execution_service(request).submit(body)
        return Accepted.model_validate(snapshot, from_attributes=True)

    @app.get("/internal/v1/executions", dependencies=[Depends(require_execution)])
    async def find(request: Request, requestId: UUID) -> Snapshot:
        return await execution_service(request).get(request_id=requestId)

    @app.get(
        "/internal/v1/executions/{execution_id}",
        dependencies=[Depends(require_execution)],
    )
    async def get(request: Request, execution_id: UUID) -> Snapshot:
        return await execution_service(request).get(execution_id)

    @app.get(
        "/internal/v1/executions/{execution_id}/events",
        dependencies=[Depends(require_execution)],
    )
    async def events(request: Request, execution_id: UUID) -> StreamingResponse:
        service = execution_service(request)
        snapshot = await service.get(execution_id)
        return StreamingResponse(
            service.events(snapshot),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    @app.post(
        "/internal/v1/executions/{execution_id}/stop",
        dependencies=[Depends(require_execution)],
    )
    async def stop(request: Request, execution_id: UUID) -> Snapshot:
        return await execution_service(request).stop(execution_id)

    return app


def app_factory() -> FastAPI:
    return create_app(Settings())  # pyright: ignore[reportCallIssue]
