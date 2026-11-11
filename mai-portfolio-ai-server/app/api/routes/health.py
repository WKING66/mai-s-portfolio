from fastapi import APIRouter

from app.api.dependencies import BootstrapSettings, ExecutionService
from app.service.health import check_readiness

router = APIRouter(prefix="/health")


@router.get("/live")
async def live() -> dict[str, str]:
    return {"status": "live"}


@router.get("/ready")
async def ready(
    settings: BootstrapSettings, executions: ExecutionService
) -> dict[str, str]:
    await check_readiness(settings, executions)
    return {"status": "ready"}
