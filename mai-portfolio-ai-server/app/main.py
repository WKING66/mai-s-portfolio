import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from secrets import compare_digest
from typing import Annotated
from uuid import UUID
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from langchain_openai import ChatOpenAI
from psycopg import AsyncConnection, Error
from psycopg_pool import AsyncConnectionPool, PoolTimeout
from pydantic import BaseModel

from app.billing import Billing, Price
from app.chat_graph import ChatGraph
from app.config import Settings
from app.execution import ExecutionError, Executions, Snapshot, SubmitExecution
from app.model_gateway import ModelGateway
from app.preferences import (
    ChatPreferences,
    SettingsView,
    UpdatePreferences,
    read_preferences,
    update_preferences,
)
from app.wire import ExecutionRejected

logger = logging.getLogger(__name__)
bearer = HTTPBearer(auto_error=False)


class Accepted(BaseModel):
    executionId: UUID
    status: str


def create_app(settings: Settings) -> FastAPI:
    execution_token = settings.ai_execution_token.get_secret_value().encode()
    management_token = settings.ai_management_token.get_secret_value().encode()
    # 两种凭证必须隔离，避免持有执行权限的调用方同时获得配置权限。
    if compare_digest(execution_token, management_token):
        raise ValueError("Execution and management credentials must differ.")

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        async with AsyncConnectionPool[AsyncConnection](
            str(settings.ai_database_url),
            kwargs={"options": f"-c search_path={settings.ai_db_schema}"},
            min_size=1,
            max_size=8,
            timeout=3,
            max_waiting=32,
            open=False,
        ) as pool:
            billing = Billing(
                pool,
                budget=settings.ai_daily_budget,
                currency=settings.billing_currency,
                time_zone=ZoneInfo(settings.billing_time_zone),
                price_version=settings.price_version,
                enabled=settings.ai_paid_calls_enabled,
            )
            graph = None
            if (
                settings.model_provider == "openai-compatible"
                and settings.chat_base_url
                and settings.chat_model
                and settings.chat_api_key.get_secret_value()
            ):
                graph = ChatGraph(
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
            executions = Executions(
                pool,
                graph,
                concurrency=settings.execution_concurrency,
                queue_limit=settings.execution_queue_limit,
            )
            # 会话锁保证单执行进程，避免第二个进程启动时误把正在运行的问答标为中断。
            async with pool.connection() as connection:
                cursor = await connection.execute(
                    "SELECT pg_try_advisory_lock(317341, 2)"
                )
                lock = await cursor.fetchone()
                # 已有执行进程时拒绝启动，保证其活动任务状态不被恢复流程覆盖。
                if lock is None or not lock[0]:
                    raise RuntimeError("An AI execution worker is already active.")
                await connection.commit()
                await executions.recover()
                app.state.executions = executions
                app.state.pool = pool
                try:
                    yield
                finally:
                    try:
                        await executions.close()
                    finally:
                        await connection.execute("SELECT pg_advisory_unlock(317341, 2)")

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
        return SettingsView(
            revision=preferences.revision,
            persona=preferences.persona,
            model_provider=settings.model_provider,
            chat_model=settings.chat_model,
            chat_max_output_tokens=settings.chat_max_output_tokens,
            embedding_model=settings.embedding_model,
            embedding_dimensions=settings.embedding_dimensions,
        )

    @app.get("/internal/v1/settings", dependencies=[Depends(require_management)])
    async def get_settings(request: Request) -> SettingsView:
        pool: AsyncConnectionPool = request.app.state.pool
        async with pool.connection() as connection:
            return settings_view(await read_preferences(connection))

    @app.put("/internal/v1/settings", dependencies=[Depends(require_management)])
    async def put_settings(request: Request, body: UpdatePreferences) -> SettingsView:
        pool: AsyncConnectionPool = request.app.state.pool
        async with pool.connection() as connection:
            return settings_view(await update_preferences(connection, body))

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

    @app.exception_handler(Error)
    @app.exception_handler(PoolTimeout)
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
        pool: AsyncConnectionPool = request.app.state.pool
        async with pool.connection() as connection:
            await connection.execute("SELECT 1 FROM execution LIMIT 1")
            await connection.execute("SELECT 1 FROM model_attempt LIMIT 1")
            await read_preferences(connection)
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
        return Accepted(executionId=snapshot.execution_id, status=snapshot.status)

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
