from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import CheckConstraint, DateTime, Numeric, Text, false, func, or_
from sqlmodel import Field, SQLModel, col, select

from app.storage.database import Sessions

ModelKind = Literal["chat", "embedding"]
BILLING_ZONE = ZoneInfo("Asia/Shanghai")


class ModelAttempt(SQLModel, table=True):
    __tablename__ = "model_attempt"  # pyright: ignore[reportAssignmentType]
    __table_args__ = (
        CheckConstraint("kind IN ('chat', 'embedding')"),
        CheckConstraint("ceiling > 0"),
        CheckConstraint("actual BETWEEN 0 AND ceiling"),
    )

    attempt_id: UUID = Field(primary_key=True)
    kind: ModelKind = Field(sa_type=Text)
    billing_day: date
    currency: str = Field(sa_type=Text)
    price_version: str = Field(sa_type=Text)
    ceiling: Decimal = Field(sa_type=Numeric)
    actual: Decimal | None = Field(default=None, sa_type=Numeric)
    bounds_exceeded: bool = Field(
        default=False, sa_column_kwargs={"server_default": false()}
    )
    input_tokens: int | None = None
    output_tokens: int | None = None


class PendingBudgetReport(SQLModel, table=True):
    __tablename__ = "pending_budget_report"  # pyright: ignore[reportAssignmentType]

    attempt_id: UUID = Field(primary_key=True)
    # 两个用量都为空表示未外呼，需撤销预占。有用量时按原尝试编号补报。
    input_tokens: int | None
    output_tokens: int | None
    retry_at: datetime = Field(sa_type=DateTime(timezone=True))


async def legacy_usage(sessions: Sessions, currency: str) -> dict[str, object]:
    """只读上报切换前的旧账本占用，Java 合并两份记录后作额度判断。"""
    day = datetime.now(BILLING_ZONE).date()
    # 旧账本只记日期，旧时区的当日费用可能落在上海日期的前后一天。切换时保守计入。
    adjacent_day = timedelta(days=1)
    async with sessions() as session:
        totals = await session.exec(
            select(
                func.coalesce(
                    func.sum(func.coalesce(ModelAttempt.actual, ModelAttempt.ceiling)), 0
                ),
                func.coalesce(
                    func.bool_or(
                        or_(
                            col(ModelAttempt.currency) != currency,
                            col(ModelAttempt.bounds_exceeded),
                        )
                    ),
                    false(),
                ),
            ).where(
                or_(
                    col(ModelAttempt.actual).is_(None),
                    col(ModelAttempt.billing_day).between(day - adjacent_day, day + adjacent_day),
                )
            )
        )
        occupied, blocked = totals.one()
        return {"billingDay": day.isoformat(), "occupied": str(occupied), "blocked": blocked}
