import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID

from httpx import AsyncClient, RequestError, Response
from sqlmodel import col, select

from app.service.billing import PendingBudgetReport, legacy_usage
from app.storage.database import Sessions

logger = logging.getLogger(__name__)
USAGE_RETRY_DELAY = timedelta(seconds=30)
USAGE_RETRY_BATCH = 10


class BillingRejected(Exception):
    """Java 未批准本次模型外呼或拒绝供应商用量。"""


@dataclass(frozen=True)
class Price:
    input_per_million: Decimal
    output_per_million: Decimal = Decimal(0)


class ModelBudgetClient:
    """把模型报价和可信用量交给 Java，预占成功后才允许外呼。"""

    def __init__(
        self, sessions: Sessions, http: AsyncClient, base_url: str, server_token: str
    ) -> None:
        self.sessions = sessions
        self.http = http
        self.base_url = base_url.rstrip("/")
        self.headers = {"Authorization": f"Bearer {server_token}"}

    async def reserve(
        self,
        attempt_id: UUID,
        *,
        price: Price,
        input_limit: int,
        output_limit: int,
        currency: str,
        price_version: str,
    ) -> None:
        if not self.base_url:
            raise BillingRejected("Java model budget service is not configured.")
        legacy = await legacy_usage(self.sessions, currency)
        try:
            response = await self._post(
                "reservations",
                {
                    "attemptId": str(attempt_id),
                    "priceVersion": price_version,
                    "currency": currency,
                    "inputPrice": str(price.input_per_million),
                    "outputPrice": str(price.output_per_million),
                    "inputLimit": input_limit,
                    "outputLimit": output_limit,
                    "legacyUsage": legacy,
                },
            )
        except RequestError as error:
            await self._queue_report(attempt_id, None, None)
            raise BillingRejected("Java model budget reservation was not confirmed.") from error
        except asyncio.CancelledError:
            await self._queue_report(attempt_id, None, None)
            raise
        if response.status_code >= 500:
            await self._queue_report(attempt_id, None, None)
        if response.status_code != 204:
            raise BillingRejected("Java model budget service denied the request.")

    async def settle(self, attempt_id: UUID, input_tokens: int, output_tokens: int) -> None:
        if input_tokens < 0 or output_tokens < 0 or input_tokens + output_tokens == 0:
            return
        # 先落实际用量。Java 重启或请求回包丢失时，可用原编号补报，已生成正文仍可交付。
        await self._queue_report(attempt_id, input_tokens, output_tokens)
        status = await self._report(attempt_id, input_tokens, output_tokens)
        if status == 409:
            raise BillingRejected("Provider usage exceeded the approved bounds.")

    async def _queue_report(
        self, attempt_id: UUID, input_tokens: int | None, output_tokens: int | None
    ) -> None:
        # 未确认的预占从未触发上游，可以撤销。上游已开始后只允许上报实际用量。
        async with self.sessions.begin() as session:
            session.add(
                PendingBudgetReport(
                    attempt_id=attempt_id,
                    input_tokens=input_tokens,
                    output_tokens=output_tokens,
                    retry_at=datetime.now(UTC) + USAGE_RETRY_DELAY,
                )
            )

    async def retry_pending_reports(self) -> None:
        if not self.base_url:
            return
        async with self.sessions() as session:
            pending = (
                await session.exec(
                    select(
                        PendingBudgetReport.attempt_id,
                        PendingBudgetReport.input_tokens,
                        PendingBudgetReport.output_tokens,
                    )
                    .where(col(PendingBudgetReport.retry_at) <= datetime.now(UTC))
                    .order_by(col(PendingBudgetReport.retry_at), col(PendingBudgetReport.attempt_id))
                    .limit(USAGE_RETRY_BATCH)
                )
            ).all()
        for attempt_id, input_tokens, output_tokens in pending:
            await self._report(attempt_id, input_tokens, output_tokens)

    async def _report(
        self, attempt_id: UUID, input_tokens: int | None, output_tokens: int | None
    ) -> int | None:
        release = input_tokens is None
        try:
            if release:
                response = await self.http.delete(
                    f"{self.base_url}/reservations/{attempt_id}", headers=self.headers
                )
            else:
                response = await self._post(
                    "settlements",
                    {
                        "attemptId": str(attempt_id),
                        "inputTokens": input_tokens,
                        "outputTokens": output_tokens,
                    },
                )
            status = response.status_code
        except RequestError:
            status = None
        async with self.sessions.begin() as session:
            usage = await session.get(PendingBudgetReport, attempt_id)
            if usage is not None:
                if status == 204 or (status == 409 and not release):
                    await session.delete(usage)
                else:
                    usage.retry_at = datetime.now(UTC) + USAGE_RETRY_DELAY
        if status == 409 and not release:
            logger.warning("Provider usage exceeded approved bounds. attempt_id=%s", attempt_id)
        return status

    async def _post(self, operation: str, payload: dict[str, object]) -> Response:
        return await self.http.post(
            f"{self.base_url}/{operation}", headers=self.headers, json=payload
        )
