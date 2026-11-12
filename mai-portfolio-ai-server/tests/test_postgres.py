import asyncio
import os
import sys
from base64 import urlsafe_b64encode
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from json import loads
from uuid import uuid4

import httpx
import pytest
from pydantic import PostgresDsn, SecretStr
from sqlalchemy import delete
from sqlalchemy import update as update_rows
from sqlmodel import col
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import Settings
from app.main import create_app
from app.provider.java_budget import BillingRejected, JavaBudget, Price
from app.service.billing import (
    BILLING_ZONE,
    ModelAttempt,
    PendingBudgetReport,
    legacy_usage,
)
from app.service.execution import Execution, Executions, lock_submission
from app.storage.database import Sessions, create_database

TEST_DATABASE = os.getenv("AI_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(
    not TEST_DATABASE, reason="Requires isolated migrated PostgreSQL."
)


def test_legacy_billing_reports_today_and_unknown_usage() -> None:
    database_url = TEST_DATABASE
    assert database_url

    async def check() -> None:
        engine = create_database(database_url, "site_guide_test")
        sessions = Sessions(engine, class_=AsyncSession)
        ids = [uuid4(), uuid4(), uuid4(), uuid4()]
        today = datetime.now(BILLING_ZONE).date()
        try:
            async with sessions.begin() as session:
                for attempt_id, day, actual in (
                    (ids[0], today, Decimal("0.1")),
                    (ids[1], today - timedelta(days=1), None),
                    (ids[2], today - timedelta(days=1), Decimal("0.1")),
                    (ids[3], today - timedelta(days=2), Decimal("0.4")),
                ):
                    session.add(
                        ModelAttempt(
                            attempt_id=attempt_id,
                            kind="chat",
                            billing_day=day,
                            currency="CNY",
                            price_version="fixture",
                            ceiling=Decimal("0.5"),
                            actual=actual,
                        )
                    )
            usage = await legacy_usage(sessions, "CNY")
            assert Decimal(str(usage["occupied"])) == Decimal("0.7")
            assert usage["blocked"] is False
            assert (await legacy_usage(sessions, "USD"))["blocked"] is True
        finally:
            async with sessions.begin() as session:
                await session.exec(
                    delete(ModelAttempt).where(col(ModelAttempt.attempt_id).in_(ids))
                )
            await engine.dispose()

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )


def test_budget_callback_replays_known_usage_and_unconfirmed_reservations() -> None:
    database_url = TEST_DATABASE
    assert database_url

    async def check() -> None:
        engine = create_database(database_url, "site_guide_test")
        sessions = Sessions(engine, class_=AsyncSession)
        settled_id, released_id = uuid4(), uuid4()
        received: list[tuple[str, str]] = []
        available = False

        def respond(request: httpx.Request) -> httpx.Response:
            received.append((request.method, request.url.path))
            if request.method == "POST" and request.url.path.endswith("/reservations"):
                raise httpx.ReadTimeout("response lost", request=request)
            if request.url.path.endswith("/settlements"):
                assert loads(request.content) == {
                    "attemptId": str(settled_id),
                    "inputTokens": 4,
                    "outputTokens": 2,
                }
            return httpx.Response(204 if available else 503)

        try:
            async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
                budget = JavaBudget(sessions, http, "http://localhost/budget", "fixture")
                await budget.settle(settled_id, 4, 2)
                with pytest.raises(BillingRejected):
                    await budget.reserve(
                        released_id,
                        price=Price(Decimal(1)),
                        input_limit=100,
                        output_limit=0,
                        currency="CNY",
                        price_version="fixture",
                    )
                async with sessions.begin() as session:
                    settlement = await session.get(PendingBudgetReport, settled_id)
                    release = await session.get(PendingBudgetReport, released_id)
                    assert settlement is not None and settlement.input_tokens == 4
                    assert release is not None and release.input_tokens is None
                    due = datetime.now(UTC) - timedelta(seconds=1)
                    settlement.retry_at = due
                    release.retry_at = due
                available = True
                # 新适配器从 PostgreSQL 重读待补报记录，模拟工作进程重启。
                recovered = JavaBudget(sessions, http, "http://localhost/budget", "fixture")
                await recovered.retry_pending_reports()
                async with sessions() as session:
                    assert await session.get(PendingBudgetReport, settled_id) is None
                    assert await session.get(PendingBudgetReport, released_id) is None
                assert received.count(("POST", "/budget/settlements")) == 2
                assert received.count(("DELETE", f"/budget/reservations/{released_id}")) == 1
        finally:
            async with sessions.begin() as session:
                await session.exec(
                    delete(PendingBudgetReport).where(
                        col(PendingBudgetReport.attempt_id).in_([settled_id, released_id])
                    )
                )
            await engine.dispose()

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )


def test_request_lookup_waits_for_acceptance_and_recovers_orphan() -> None:
    database_url = TEST_DATABASE
    assert database_url

    async def check() -> None:
        engine = create_database(database_url, "site_guide_test")
        sessions = Sessions(engine, class_=AsyncSession)
        service = Executions(sessions, lambda _: None, concurrency=1, queue_limit=0)
        request_id, execution_id = uuid4(), uuid4()
        try:
            async with sessions.begin() as session:
                await lock_submission(session, request_id)
                lookup = asyncio.create_task(service.get(request_id=request_id))
                # 并发负查必须等受理事务结束，不能在未提交时报告不存在。
                done, _ = await asyncio.wait({lookup}, timeout=0.1)
                assert not done
                session.add(
                    Execution(
                        request_id=request_id,
                        execution_id=execution_id,
                        status="running",
                        text="partial answer",
                        input_hash="fixture",
                    )
                )
            snapshot = await asyncio.wait_for(lookup, timeout=3)
            assert snapshot.execution_id == execution_id
            assert snapshot.status == "interrupted"
            assert snapshot.text == "partial answer"
            async with sessions.begin() as session:
                await session.exec(
                    update_rows(Execution)
                    .where(col(Execution.execution_id) == execution_id)
                    .values(status="completed", finished_at=datetime.now(UTC) - timedelta(days=2))
                )
            await service.expire_answers()
            expired = await service.get(request_id=request_id)
            assert expired.execution_id == execution_id
            assert expired.text == ""
            assert expired.status == "completed"
            assert expired.error is not None
            assert expired.error.code == "EXECUTION_TEXT_EXPIRED"
        finally:
            async with sessions.begin() as session:
                await session.exec(
                    delete(Execution).where(col(Execution.execution_id) == execution_id)
                )
            await engine.dispose()

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )


def test_execution_routes_and_settings_revision() -> None:
    assert TEST_DATABASE
    server_token = "e" * 32
    settings = Settings(
        ai_database_url=PostgresDsn(TEST_DATABASE),
        ai_db_schema="site_guide_test",
        ai_server_token=SecretStr(server_token),
        model_provider="openai-compatible",
        chat_base_url="https://example.invalid/v1",
        chat_api_key=SecretStr("never-sent"),
        config_encryption_key=SecretStr(urlsafe_b64encode(bytes(32)).decode()),
        chat_model="fixture",
    )
    app = create_app(settings)

    async def check() -> None:
        async with (
            app.router.lifespan_context(app),
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url="http://test",
                headers={"Authorization": f"Bearer {server_token}"},
            ) as client,
        ):
            assert (await client.get("/internal/v1/health/ready")).json() == {
                "status": "ready"
            }
            path = "/internal/v1/executions"
            assert (await client.get(path, params={"requestId": "bad-id"})).json()[
                "code"
            ] == "INVALID_INPUT"
            missing = await client.get(f"{path}/{uuid4()}/events")
            assert missing.status_code == 404
            assert missing.headers["content-type"] == "application/json"
            request_id = str(uuid4())
            payload = {
                "requestId": request_id,
                "question": "Hello",
                "history": [],
                "acceptBefore": (datetime.now(UTC) + timedelta(minutes=1)).isoformat(),
            }
            response = await client.post(path, json=payload)
            assert response.status_code == 202, response.text
            assert set(response.json()) == {"executionId", "status"}
            execution_path = f"{path}/{response.json()['executionId']}"
            response = await client.get(f"{execution_path}/events")
            assert response.headers["content-type"].startswith("text/event-stream")
            assert "no-store" in response.headers["cache-control"]
            assert "event: snapshot" in response.text
            assert "event: terminal" in response.text
            assert "MODEL_BUDGET_UNAVAILABLE" in response.text
            duplicate = await client.post(path, json=payload)
            assert duplicate.json()["executionId"] == execution_path.rsplit("/", 1)[-1]
            conflict = await client.post(
                path, json={**payload, "question": "Different"}
            )
            assert conflict.status_code == 409
            lookup = await client.get(path, params={"requestId": request_id})
            stopped = await client.post(f"{execution_path}/stop")
            assert lookup.json() == stopped.json()
            assert stopped.json()["status"] == "failed"
            assert set(stopped.json()) == {
                "requestId",
                "executionId",
                "status",
                "text",
                "sources",
                "error",
            }
            settings_path = "/internal/v1/settings"
            assert (
                await client.get(settings_path, headers={"Authorization": ""})
            ).status_code == 401
            management = {"Authorization": f"Bearer {server_token}"}
            revision = (await client.get(settings_path, headers=management)).json()[
                "revision"
            ]
            model_name = f"configured-{uuid4()}"
            provider_url = f"https://chat-{uuid4()}.example.invalid/v1"
            update = {
                "expectedRevision": revision,
                "persona": "Friendly and direct.",
                "runtime": {"chat_model": model_name, "chat_base_url": provider_url},
                "chatApiKey": "test-key-written-once",
            }
            invalid = await client.put(
                settings_path,
                json={**update, "persona": " "},
                headers=management,
            )
            assert invalid.status_code == 422
            assert invalid.json()["code"] == "INVALID_INPUT"
            invalid_key = await client.put(
                settings_path, json={**update, "chatApiKey": " "}, headers=management
            )
            assert invalid_key.status_code == 422
            assert invalid_key.json()["code"] == "INVALID_SETTINGS"
            assert "chatApiKey" not in invalid_key.text
            malformed_key = await client.put(
                settings_path,
                json={**update, "chatApiKey": "fake-token\nsecond-line"},
                headers=management,
            )
            assert malformed_key.status_code == 422
            assert malformed_key.json()["code"] == "INVALID_SETTINGS"
            assert "fake-token" not in malformed_key.text
            without_key = {
                name: value for name, value in update.items() if name != "chatApiKey"
            }
            rejected = await client.put(
                settings_path, json=without_key, headers=management
            )
            assert rejected.json()["code"] == "PROVIDER_KEY_REQUIRED"
            budget_policy = await client.put(
                settings_path,
                json={**update, "runtime": {**update["runtime"], "ai_daily_budget": "1"}},
                headers=management,
            )
            assert budget_policy.status_code == 422
            updated = await client.put(settings_path, json=update, headers=management)
            assert "ai_daily_budget" not in updated.json()["runtime"]
            assert updated.json()["runtime"]["chat_input_price_per_million"] == "0"
            assert updated.json()["revision"] == revision + 1
            assert updated.json()["chatModel"] == model_name
            assert updated.json()["runtime"]["chat_max_output_tokens"] == 512
            assert updated.json()["chatKeyConfigured"] is True
            assert "never-sent" not in updated.text
            assert "test-key-written-once" not in updated.text
            stored = await client.get(settings_path, headers=management)
            assert stored.json()["chatModel"] == model_name
            assert stored.json()["runtime"]["chat_base_url"] == provider_url
            forbidden = await client.put(
                settings_path,
                json={
                    **update,
                    "expectedRevision": revision + 1,
                    "runtime": {"ai_server_token": "overwrite"},
                },
                headers=management,
            )
            assert forbidden.status_code == 422
            assert forbidden.json()["code"] == "INVALID_SETTINGS"
            assert "overwrite" not in forbidden.text
            stale = await client.put(settings_path, json=update, headers=management)
            assert stale.status_code == 409

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )
