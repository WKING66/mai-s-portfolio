from fastapi import APIRouter, Depends

from app.api.dependencies import require_server
from app.api.routes import model_catalog

router = APIRouter(prefix="/settings", dependencies=[Depends(require_server)])
router.include_router(model_catalog.router)
