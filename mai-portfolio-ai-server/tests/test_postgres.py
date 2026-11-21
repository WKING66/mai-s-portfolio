import asyncio
import os
import sys
from base64 import urlsafe_b64encode
from datetime import UTC, datetime, timedelta
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
from app.service.execution import Execution, ExecutionCode, Executions, lock_submission
from app.storage.database import Sessions, create_database

TEST_DATABASE = os.getenv("AI_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(
    not TEST_DATABASE, reason="Requires isolated migrated PostgreSQL."
)


def test_request_lookup_waits_for_acceptance_and_recovers_orphan() -> None:
    database_url = TEST_DATABASE
    assert database_url

    async def check() -> None:
        engine = create_database(database_url, os.getenv("AI_TEST_DB_SCHEMA", "site_guide_test"))
        sessions = Sessions(engine, class_=AsyncSession)
        async def no_configuration(_: AsyncSession) -> None:
            return None

        service = Executions(sessions, no_configuration, concurrency=1, queue_limit=0)
        request_id = uuid4()
        try:
            async with sessions.begin() as session:
                await lock_submission(session, request_id)
                lookup = asyncio.create_task(service.get(request_id=request_id))
                # 并发负查必须等受理事务结束，不能在未提交时报告不存在。
                done, _ = await asyncio.wait({lookup}, timeout=0.1)
                assert not done
                execution = Execution(
                    request_id=request_id,
                    status=ExecutionCode.RUNNING,
                    text="partial answer",
                    input_hash="fixture",
                )
                session.add(execution)
                await session.flush()
                execution_id = execution.id
            snapshot = await asyncio.wait_for(lookup, timeout=3)
            assert snapshot.execution_id == str(execution_id)
            assert snapshot.status == "interrupted"
            assert snapshot.text == "partial answer"
            # 新受理暂时占有锁时，读取终态快照仍能立即返回。
            async with service.submissions:
                visible = await asyncio.wait_for(service.get(execution_id), timeout=3)
                assert visible.status == "interrupted" and visible.text == "partial answer"
            async with sessions.begin() as session:
                await session.exec(
                    update_rows(Execution)
                    .where(col(Execution.id) == execution_id)
                    .values(
                        status=ExecutionCode.COMPLETED,
                        finished_at=datetime.now(UTC) - timedelta(days=2),
                    )
                )
            await service.expire_answers()
            expired = await service.get(request_id=request_id)
            assert expired.execution_id == str(execution_id)
            assert expired.text == ""
            assert expired.status == "completed"
            assert expired.error is not None
            assert expired.error.code == "EXECUTION_TEXT_EXPIRED"
        finally:
            async with sessions.begin() as session:
                await session.exec(
                    delete(Execution).where(col(Execution.request_id) == request_id)
                )
            await engine.dispose()

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )


def test_execution_routes_and_catalog(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CHAT_MODEL", "ignored-environment-model")
    monkeypatch.setenv("CHAT_BASE_URL", "https://ignored.example.invalid/v1")
    monkeypatch.setenv("CHAT_API_KEY", "ignored-environment-key")
    assert TEST_DATABASE
    def reply(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=(
                'data: {"id":"fixture","created":0,"model":"fixture","choices":[{"index":0,'
                '"delta":{"role":"assistant","content":"回答"}}]}\n\n'
                'data: {"id":"fixture","created":0,"model":"fixture","choices":[],'
                '"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}\n\n'
                'data: [DONE]\n\n'
            ).encode(),
            headers={"Content-Type": "text/event-stream"},
        )

    monkeypatch.setattr(
        "app.main.provider_http_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(reply)),
    )
    server_token = "e" * 32
    settings = Settings(
        ai_database_url=PostgresDsn(TEST_DATABASE),
        ai_db_schema="site_guide_test",
        ai_server_token=SecretStr(server_token),
        config_encryption_key=SecretStr(urlsafe_b64encode(bytes(32)).decode()),
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
            settings_path = "/internal/v1/settings"
            assert (await client.get(settings_path)).status_code == 404
            assert (await client.put(settings_path, json={})).status_code == 404

            catalog = settings_path + "/catalog"
            provider = await client.post(catalog + "/providers", json={
                "title": str(uuid4()), "providerCode": "fixture", "apiProtocol": 1,
                "baseUrl": "https://example.invalid/v1", "apiKey": "never-sent",
            })
            assert provider.status_code == 201, provider.text
            model = await client.post(catalog + "/models", json={
                "modelProviderId": provider.json()["id"], "modelKey": "fixture",
                "modelNickname": "fixture", "modelType": 1, "config": {},
            })
            assert model.status_code == 201, model.text
            original = (await client.get(catalog)).json()["routes"][0]["modelIds"]
            selected = [model.json()["id"]]
            route = await client.put(catalog + "/routes/1", json={
                "expectedModelIds": original, "modelIds": selected,
            })
            assert route.status_code == 200, route.text
            path = "/internal/v1/executions"
            assert (await client.get(path, params={"requestId": "bad-id"})).json()[
                "code"
            ] == "INVALID_INPUT"
            missing = await client.get(f"{path}/9223372036854775807/events")
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
            assert "回答" in response.text
            duplicate = await client.post(path, json=payload)
            assert duplicate.json()["executionId"] == execution_path.rsplit("/", 1)[-1]
            conflict = await client.post(
                path, json={**payload, "question": "Different"}
            )
            assert conflict.status_code == 409
            lookup = await client.get(path, params={"requestId": request_id})
            stopped = await client.post(f"{execution_path}/stop")
            assert lookup.json() == stopped.json()
            assert stopped.json()["status"] == "completed"
            assert (stopped.json()["inputTokens"], stopped.json()["outputTokens"]) == (5, 2)
            assert stopped.json()["usageStatus"] == 1
            assert set(stopped.json()) == {
                "requestId",
                "executionId",
                "status",
                "text",
                "sources",
                "error",
                "inputTokens",
                "outputTokens",
                "usageStatus",
            }
            await client.put(catalog + "/routes/1", json={
                "expectedModelIds": selected, "modelIds": original,
            })
            assert (await client.get(catalog, headers={"Authorization": ""})).status_code == 401
            provider_snapshot = (await client.get(catalog)).json()
            assert "never-sent" not in str(provider_snapshot)

    asyncio.run(
        check(),
        loop_factory=asyncio.SelectorEventLoop if sys.platform == "win32" else None,
    )
