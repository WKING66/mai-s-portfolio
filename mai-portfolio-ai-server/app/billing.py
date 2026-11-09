from dataclasses import dataclass
from datetime import datetime
from decimal import ROUND_CEILING, Decimal
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg_pool import AsyncConnectionPool

ModelKind = Literal["chat", "embedding"]
MICRO = Decimal("0.000001")


class BillingRejected(Exception):
    """The upstream call must not start."""


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


class Billing:
    def __init__(
        self,
        pool: AsyncConnectionPool,
        *,
        budget: Decimal,
        currency: str,
        time_zone: ZoneInfo,
        price_version: str,
        enabled: bool,
    ) -> None:
        self.pool = pool
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
        async with self.pool.connection() as connection, connection.transaction():
            # Every paid model shares one lock, including across billing days.
            await connection.execute("SELECT pg_advisory_xact_lock(317341, 1)")
            day = datetime.now(self.time_zone).date()
            cursor = await connection.execute(
                """
                SELECT COALESCE(SUM(COALESCE(actual, ceiling)), 0),
                       COALESCE(bool_or(currency <> %s OR bounds_exceeded), false)
                FROM model_attempt
                WHERE actual IS NULL OR billing_day = %s
                """,
                (self.currency, day),
            )
            totals = await cursor.fetchone()
            if totals is None or totals[1] or totals[0] + ceiling > self.budget:
                raise BillingRejected("Model budget is unavailable.")
            inserted = await connection.execute(
                """
                INSERT INTO model_attempt
                    (attempt_id, kind, billing_day, currency, price_version, ceiling)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (attempt_id) DO NOTHING
                """,
                (attempt_id, kind, day, self.currency, self.price_version, ceiling),
            )
            if inserted.rowcount != 1:
                raise BillingRejected("This paid attempt already exists.")
        return Reservation(attempt_id, ceiling, input_limit, output_limit, price)

    async def settle(
        self,
        reservation: Reservation,
        input_tokens: int,
        output_tokens: int,
    ) -> None:
        if input_tokens < 0 or output_tokens < 0 or input_tokens + output_tokens == 0:
            return
        if (
            input_tokens > reservation.input_limit
            or output_tokens > reservation.output_limit
        ):
            async with self.pool.connection() as connection:
                await connection.execute(
                    """
                    UPDATE model_attempt SET bounds_exceeded = true,
                        input_tokens = %s, output_tokens = %s
                    WHERE attempt_id = %s AND actual IS NULL
                    """,
                    (input_tokens, output_tokens, reservation.attempt_id),
                )
            raise BillingRejected(
                "Provider usage exceeded the verified billing bounds."
            )
        actual = reservation.price.cost(input_tokens, output_tokens)
        async with self.pool.connection() as connection:
            await connection.execute(
                """
                UPDATE model_attempt SET actual = %s, input_tokens = %s, output_tokens = %s
                WHERE attempt_id = %s AND actual IS NULL AND NOT bounds_exceeded
                """,
                (actual, input_tokens, output_tokens, reservation.attempt_id),
            )
