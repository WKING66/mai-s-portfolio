from secrets import compare_digest
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings
from app.contracts.wire import ExecutionRejected
from app.service.execution import Executions
from app.storage.database import Sessions

bearer = HTTPBearer(auto_error=False)


async def get_settings(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


async def get_executions(request: Request) -> Executions:
    executions: Executions = request.app.state.executions
    return executions


async def get_sessions(request: Request) -> Sessions:
    sessions: Sessions = request.app.state.sessions
    return sessions


BootstrapSettings = Annotated[Settings, Depends(get_settings)]
ExecutionService = Annotated[Executions, Depends(get_executions)]
DatabaseSessions = Annotated[Sessions, Depends(get_sessions)]


async def require_server(
    credential: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    settings: BootstrapSettings,
) -> None:
    # 网站统一管理访客与站长权限，内部接口只验证站间凭证。
    if credential is None or not compare_digest(
        credential.credentials.encode(),
        settings.ai_server_token.get_secret_value().encode(),
    ):
        raise ExecutionRejected(401, "UNAUTHORIZED", "Invalid AI server credential.")
