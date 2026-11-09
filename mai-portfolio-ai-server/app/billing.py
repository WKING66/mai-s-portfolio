from dataclasses import dataclass
from datetime import date, datetime
from decimal import ROUND_CEILING, Decimal
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import CheckConstraint, Numeric, Text, false, func, or_, update
from sqlalchemy.dialects.postgresql import insert
from sqlmodel import Field, SQLModel, col, select

from app.database import Sessions

ModelKind = Literal["chat", "embedding"]
MICRO = Decimal("0.000001")


class BillingRejected(Exception):
    """费用条件不成立时，拒绝启动上游调用。"""


@dataclass(frozen=True)
class Price:
    input_per_million: Decimal
    output_per_million: Decimal = Decimal(0)

    def cost(self, input_tokens: int, output_tokens: int) -> Decimal:
        return (
            (
                self.input_per_million * input_tokens
                + self.output_per_million * output_tokens
            )
            / 1_000_000
        ).quantize(MICRO, rounding=ROUND_CEILING)


@dataclass(frozen=True)
class Reservation:
    attempt_id: UUID
    ceiling: Decimal
    input_limit: int
    output_limit: int
    price: Price


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


class Billing:
    def __init__(
        self,
        sessions: Sessions,
        *,
        budget: Decimal,
        currency: str,
        time_zone: ZoneInfo,
        price_version: str,
        enabled: bool,
    ) -> None:
        self.sessions = sessions
        self.budget = budget
        self.currency = currency
        self.time_zone = time_zone
        self.price_version = price_version
        self.enabled = enabled

    async def reserve(
        self,
        attempt_id: UUID,
        kind: ModelKind,
        price: Price,
        input_limit: int,
        output_limit: int,
    ) -> Reservation:
        # 1. 缺少开关、定价或可验证的费用上界时拒绝外呼，否则无法保证预算硬上限。
        if (
            not self.enabled
            or not self.currency
            or not self.price_version
            or not self.budget.is_finite()
            or self.budget <= 0
            or input_limit <= 0
            or output_limit < 0
            or not price.input_per_million.is_finite()
            or price.input_per_million <= 0
            or not price.output_per_million.is_finite()
            or price.output_per_million < 0
            or (kind == "chat" and (output_limit == 0 or price.output_per_million == 0))
        ):
            raise BillingRejected("Billing is disabled or pricing is incomplete.")
        ceiling = price.cost(input_limit, output_limit)
        async with self.sessions.begin() as session:
            # 2. Chat 和 Embedding 共用预算锁，避免并发请求用同一份余额放行。
            await session.exec(select(func.pg_advisory_xact_lock(317341, 1)))
            day = datetime.now(self.time_zone).date()
            totals = await session.exec(
                select(
                    func.coalesce(
                        func.sum(
                            func.coalesce(ModelAttempt.actual, ModelAttempt.ceiling)
                        ),
                        0,
                    ),
                    func.coalesce(
                        func.bool_or(
                            or_(
                                col(ModelAttempt.currency) != self.currency,
                                col(ModelAttempt.bounds_exceeded),
                            )
                        ),
                        false(),
                    ),
                ).where(
                    or_(
                        col(ModelAttempt.actual).is_(None),
                        col(ModelAttempt.billing_day) == day,
                    )
                )
            )
            # 跨日的未知用量继续占用。币种冲突或已发现供应商用量超界时暂停收费。
            occupied, blocked = totals.one()
            if blocked or occupied + ceiling > self.budget:
                raise BillingRejected("Model budget is unavailable.")
            # 3. 尝试标识唯一。回包不明也不能重新使用旧标识发起收费。
            inserted = await session.scalar(
                insert(ModelAttempt)
                .values(
                    attempt_id=attempt_id,
                    kind=kind,
                    billing_day=day,
                    currency=self.currency,
                    price_version=self.price_version,
                    ceiling=ceiling,
                )
                .on_conflict_do_nothing(index_elements=[col(ModelAttempt.attempt_id)])
                .returning(col(ModelAttempt.attempt_id))
            )
            if inserted is None:
                raise BillingRejected("This paid attempt already exists.")
        return Reservation(attempt_id, ceiling, input_limit, output_limit, price)

    async def settle(
        self,
        reservation: Reservation,
        input_tokens: int,
        output_tokens: int,
    ) -> None:
        # 供应商漏报或回报无效时保留原预留，避免把可能已发生的费用当作零。
        if input_tokens < 0 or output_tokens < 0 or input_tokens + output_tokens == 0:
            return
        pending = (
            update(ModelAttempt)
            .where(
                col(ModelAttempt.attempt_id) == reservation.attempt_id,
                col(ModelAttempt.actual).is_(None),
            )
            .values(input_tokens=input_tokens, output_tokens=output_tokens)
        )
        # 供应商回报超界说明原计费前提已失效。持久化异常，避免重启后继续放行。
        if (
            input_tokens > reservation.input_limit
            or output_tokens > reservation.output_limit
        ):
            async with self.sessions.begin() as session:
                await session.exec(pending.values(bounds_exceeded=True))
            raise BillingRejected(
                "Provider usage exceeded the verified billing bounds."
            )

        async with self.sessions.begin() as session:
            await session.exec(
                pending.where(col(ModelAttempt.bounds_exceeded).is_(False)).values(
                    actual=reservation.price.cost(input_tokens, output_tokens)
                )
            )
