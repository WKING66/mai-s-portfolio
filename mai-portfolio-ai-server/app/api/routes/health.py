from fastapi import APIRouter

from app.api.dependencies import BootstrapSettings, DatabaseSessions, ExecutionService
from app.service.health import check_readiness

router = APIRouter(prefix="/health")


@router.get("/live")
async def live() -> dict[str, str]:
    return {"status": "live"}


@router.get("/ready")
async def ready(
    settings: BootstrapSettings, sessions: DatabaseSessions, executions: ExecutionService
) -> dict[str, str]:
    executions.require_worker()
    await check_readiness(settings, sessions)
    return {"status": "ready"}
