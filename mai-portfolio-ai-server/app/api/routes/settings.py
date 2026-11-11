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
    read_preferences,
    settings_view,
)

router = APIRouter(prefix="/settings", dependencies=[Depends(require_server)])


@router.get("")
async def get_settings(
    settings: BootstrapSettings, sessions: DatabaseSessions
) -> SettingsView:
    async with sessions() as session:
        return settings_view(settings, await read_preferences(session))


@router.put("")
async def put_settings(
    settings: BootstrapSettings,
    body: UpdatePreferences,
    executions: ExecutionService,
) -> SettingsView:
    return await executions.update_settings(settings, body)
