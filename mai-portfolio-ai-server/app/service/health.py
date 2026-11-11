from sqlmodel import col, select

from app.config import Settings, effective_settings
from app.contracts.wire import ExecutionRejected
from app.service.billing import ModelAttempt
from app.service.execution import Execution, Executions
from app.service.preferences import read_preferences


async def check_readiness(settings: Settings, executions: Executions) -> None:
    """检查执行进程、数据库迁移和生效配置，期间不访问供应商。"""
    executions.require_worker()
    async with executions.sessions() as session:
        await session.exec(select(col(Execution.execution_id)).limit(1))
        await session.exec(select(col(ModelAttempt.attempt_id)).limit(1))
        preferences = await read_preferences(session)
        try:
            effective_settings(settings, preferences.runtime)
        except ValueError as error:
            raise ExecutionRejected(
                503, "INVALID_STORED_SETTINGS", "Stored settings are unavailable."
            ) from error
