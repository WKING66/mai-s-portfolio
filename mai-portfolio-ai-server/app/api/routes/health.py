from fastapi import APIRouter

from app.api.dependencies import DatabaseSessions, ExecutionService
from app.service.health import check_readiness

router = APIRouter(prefix="/health")


@router.get("/live")
async def live() -> dict[str, str]:
    return {"status": "live"}


@router.get("/ready")
async def ready(sessions: DatabaseSessions, executions: ExecutionService) -> dict[str, str]:
    executions.require_worker()
    await check_readiness(sessions)
    return {"status": "ready"}
