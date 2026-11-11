from fastapi import APIRouter, Depends

from app.api.dependencies import (
    BootstrapSettings,
    DatabaseSessions,
    ExecutionService,
    require_server,
)
from app.service.preferences import (
    SettingsView,
    UpdatePreferences,
    read_settings,
    write_settings,
)

router = APIRouter(prefix="/settings", dependencies=[Depends(require_server)])


@router.get("")
async def get_settings(
    settings: BootstrapSettings, sessions: DatabaseSessions
) -> SettingsView:
    return await read_settings(settings, sessions)


@router.put("")
async def put_settings(
    settings: BootstrapSettings,
    body: UpdatePreferences,
    sessions: DatabaseSessions,
    executions: ExecutionService,
) -> SettingsView:
    return await write_settings(settings, sessions, body, executions.settings_write())
