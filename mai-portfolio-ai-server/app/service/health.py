from sqlmodel import col, select

from app.config import Settings, effective_settings
from app.contracts.wire import ExecutionRejected
from app.service.billing import ModelAttempt, PendingBudgetReport
from app.service.execution import Execution
from app.service.preferences import read_preferences
from app.storage.database import Sessions


async def check_readiness(settings: Settings, sessions: Sessions) -> None:
    """检查数据库迁移和生效配置，期间不访问供应商。"""
    async with sessions() as session:
        await session.exec(select(col(Execution.execution_id)).limit(1))
        await session.exec(select(col(ModelAttempt.attempt_id)).limit(1))
        await session.exec(select(col(PendingBudgetReport.attempt_id)).limit(1))
        preferences = await read_preferences(session)
        try:
            effective_settings(settings, preferences.runtime)
        except ValueError as error:
            raise ExecutionRejected(
                503, "INVALID_STORED_SETTINGS", "Stored settings are unavailable."
            ) from error
