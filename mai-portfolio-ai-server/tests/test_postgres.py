import asyncio
import os
import sys
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx
import pytest
from psycopg import AsyncConnection
from psycopg_pool import AsyncConnectionPool

from app.billing import Billing, BillingRejected, Price
from app.config import Settings
from app.main import create_app

TEST_DATABASE = os.getenv("AI_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(
    not TEST_DATABASE, reason="Requires isolated migrated PostgreSQL."
)


def test_billing_serializes_reservations_and_retains_unknown_usage() -> None:
    assert TEST_DATABASE

    async def check() -> None:
        async with AsyncConnectionPool[AsyncConnection](
            TEST_DATABASE,
            kwargs={"options": "-c search_path=site_guide_test"},
            open=False,
        ) as pool:
            billing = Billing(
                pool,
                budget=Decimal(1),
                currency="TEST",
                time_zone=ZoneInfo("UTC"),
                price_version="fixture",
                enabled=True,
            )
            price = Price(Decimal(6000))
            first, second = await asyncio.gather(
                billing.reserve(uuid4(), "embedding", price, 100, 0),
                billing.reserve(uuid4(), "embedding", price, 100, 0),
                return_exceptions=True,
            )
            rejected = [
                result
                for result in (first, second)
                if isinstance(result, BillingRejected)
            ]
            assert len(rejected) == 1
            winner = second if isinstance(first, BillingRejected) else first
            assert not isinstance(winner, BaseException)
            async with pool.connection() as connection:
                await connection.execute(
                    "UPDATE model_attempt SET billing_day = billing_day - 1 WHERE attempt_id = %s",
                    (winner.attempt_id,),
                )
            with pytest.raises(BillingRejected):
                await billing.reserve(uuid4(), "embedding", price, 100, 0)
            await billing.settle(winner, 10, 0)
            with pytest.raises(BillingRejected):
                await billing.reserve(winner.attempt_id, "embedding", price, 100, 0)
            next_attempt = await billing.reserve(uuid4(), "embedding", price, 100, 0)
            with pytest.raises(BillingRejected):
                await billing.settle(next_attempt, 101, 0)
            with pytest.raises(BillingRejected):
                await billing.reserve(uuid4(), "embedding", price, 1, 0)

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )


def test_execution_routes_and_settings_revision() -> None:
    assert TEST_DATABASE
    execution_token = "e" * 32
    management_token = "m" * 32
    settings = Settings(
        ai_database_url=TEST_DATABASE,
        ai_db_schema="site_guide_test",
        ai_execution_token=execution_token,
        ai_management_token=management_token,
        model_provider="openai-compatible",
        chat_base_url="https://example.invalid/v1",
        chat_api_key="never-sent",
        chat_model="fixture",
    )
    app = create_app(settings)

    async def check() -> None:
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url="http://test",
                headers={"Authorization": f"Bearer {execution_token}"},
            ) as client,
        ):
            path = "/internal/v1/executions"
            request_id = str(uuid4())
            payload = {
                "requestId": request_id,
                "question": "Hello",
                "history": [],
                "acceptBefore": (datetime.now(UTC) + timedelta(minutes=1)).isoformat(),
            }
            response = await client.post(path, json=payload)
            assert response.status_code == 202, response.text
            execution_path = f"{path}/{response.json()['executionId']}"
            response = await client.get(f"{execution_path}/events")
            assert "event: snapshot" in response.text
            assert "event: terminal" in response.text
            assert "MODEL_BUDGET_UNAVAILABLE" in response.text
            duplicate = await client.post(path, json=payload)
            assert duplicate.json()["executionId"] == execution_path.rsplit("/", 1)[-1]
            conflict = await client.post(
                path, json={**payload, "question": "Different"}
            )
            assert conflict.status_code == 409
            stopped = await client.post(f"{execution_path}/stop")
            assert stopped.json()["status"] == "failed"
            settings_path = "/internal/v1/settings"
            assert (await client.get(settings_path)).status_code == 401
            management = {"Authorization": f"Bearer {management_token}"}
            revision = (await client.get(settings_path, headers=management)).json()[
                "revision"
            ]
            update = {"expectedRevision": revision, "persona": "Friendly and direct."}
            updated = await client.put(settings_path, json=update, headers=management)
            assert updated.json()["revision"] == revision + 1
            assert "never-sent" not in updated.text
            stale = await client.put(settings_path, json=update, headers=management)
            assert stale.status_code == 409

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )
