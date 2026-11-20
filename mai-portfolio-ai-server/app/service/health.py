from sqlmodel import col, select

from app.contracts.model_catalog import ModelRoute
from app.service.execution import Execution
from app.service.prompt_catalog import active_persona
from app.storage.database import Sessions


async def check_readiness(sessions: Sessions) -> None:
    """检查数据库迁移和生效配置，期间不访问供应商。"""
    async with sessions() as session:
        await session.exec(select(col(Execution.id)).limit(1))
        await session.exec(select(col(ModelRoute.id)).limit(1))
        await active_persona(session)
