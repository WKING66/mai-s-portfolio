from fastapi import APIRouter

from app.api.routes import executions, health, settings

router = APIRouter(prefix="/internal/v1")
router.include_router(executions.router)
router.include_router(health.router)
router.include_router(settings.router)
