import logging
from secrets import compare_digest
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import col, select

from app.config import (
    Settings,
    effective_settings,
)
from app.contracts.wire import ExecutionRejected
from app.service.billing import ModelAttempt
from app.service.execution import (
    Accepted,
    Execution,
    ExecutionError,
    Executions,
    Snapshot,
    SubmitExecution,
)
from app.service.preferences import (
    SettingsView,
    UpdatePreferences,
    read_preferences,
    save_settings,
    settings_view,
)
from app.storage.database import Sessions

logger = logging.getLogger(__name__)
bearer = HTTPBearer(auto_error=False)


def register_routes(app: FastAPI, settings: Settings) -> None:
    server_token = settings.ai_server_token.get_secret_value().encode()

    async def require_server(
        credential: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    ) -> None:
        # 网站统一管理访客与 OWNER 权限，内部服务只校验同一份站间凭证。
        if credential is None or not compare_digest(
            credential.credentials.encode(), server_token
        ):
            raise ExecutionRejected(
                401, "UNAUTHORIZED", "Invalid AI server credential."
            )

    @app.get("/internal/v1/settings", dependencies=[Depends(require_server)])
    async def get_settings(request: Request) -> SettingsView:
        sessions: Sessions = request.app.state.sessions
        async with sessions() as session:
            return settings_view(settings, await read_preferences(session))

    @app.put("/internal/v1/settings", dependencies=[Depends(require_server)])
    async def put_settings(request: Request, body: UpdatePreferences) -> SettingsView:
        sessions: Sessions = request.app.state.sessions
        executions = execution_service(request)
        executions.require_worker()
        async with executions.submissions, sessions.begin() as session:
            current = await read_preferences(session)
            return await save_settings(
                settings, session, body, current, bool(executions.tasks)
            )

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
        dependencies=[Depends(require_server)],
    )
    async def submit(
        request: Request,
        body: SubmitExecution,
    ) -> Accepted:
        snapshot = await execution_service(request).submit(body)
        return Accepted.model_validate(snapshot, from_attributes=True)

    @app.get("/internal/v1/executions", dependencies=[Depends(require_server)])
    async def find(request: Request, requestId: UUID) -> Snapshot:
        return await execution_service(request).get(request_id=requestId)

    @app.get(
        "/internal/v1/executions/{execution_id}",
        dependencies=[Depends(require_server)],
    )
    async def get(request: Request, execution_id: UUID) -> Snapshot:
        return await execution_service(request).get(execution_id)

    @app.get(
        "/internal/v1/executions/{execution_id}/events",
        dependencies=[Depends(require_server)],
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
        dependencies=[Depends(require_server)],
    )
    async def stop(request: Request, execution_id: UUID) -> Snapshot:
        return await execution_service(request).stop(execution_id)
