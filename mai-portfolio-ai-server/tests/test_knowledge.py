import asyncio
import hashlib
import json
import logging
import os
import subprocess
import sys
from collections.abc import AsyncIterator, Iterator
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from psycopg import Connection, sql
from pydantic import SecretStr
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from app.config import Settings
from app.knowledge import worker as worker_module
from app.knowledge.client import Claim, KnowledgeClient
from app.knowledge.projection import (
    KnowledgeValidationError,
    projection_chunks,
    projection_hash,
)
from app.knowledge.rag import KnowledgeRag, route_question
from app.knowledge.store import KnowledgeStore, PreparedChunk, StaleSequenceError
from app.knowledge.worker import KnowledgeWorker
from app.provider.embedding import EmbeddingResult, InvalidEmbedding
from app.provider.model_routing import Candidate, ModelRouting
from app.storage.database import create_database
from app.workflow.chat_graph import ChatState, ConfiguredChat

TOKEN = "test-knowledge-only-aaaaaaaaaaaaaaaa"
LOOP_FACTORY = asyncio.SelectorEventLoop if sys.platform == "win32" else None
CANDIDATE = Candidate(
    1, 2, 3, "embed-test", "https://model.example.com/v1", SecretStr("test"), {"dimensions": 2},
)


def document(markdown: str) -> dict[str, object]:
    return {"documentId": 42, "title": "标题", "markdown": markdown, "tags": []}


def future_lease(seconds: int = 120) -> str:
    return (datetime.now(UTC) + timedelta(seconds=seconds)).isoformat()


def claim(
    seq: int, projection: dict[str, object] | None = None, *,
    action: int = 1, source_type: int = 1, source_id: int = 42,
) -> Claim:
    return Claim.model_validate({
        "queueId": seq, "sourceType": source_type, "sourceId": source_id, "changeSeq": seq,
        "action": action, "buildMode": 1, "leaseToken": str(uuid4()),
        "leaseUntil": future_lease(), "attemptCount": 1,
        "projectionHash": projection_hash(projection) if projection is not None else None,
        "projection": projection,
    })


@pytest.fixture
def database() -> Iterator[tuple[str, str]]:
    url = os.getenv("AI_TEST_DATABASE_URL")
    if not url:
        pytest.skip("Requires an isolated PostgreSQL with pgvector.")
    if not url.startswith(("postgresql://", "postgres://")):
        pytest.fail("AI_TEST_DATABASE_URL must be a PostgreSQL URL.")
    with Connection.connect(url, autocommit=True) as connection:
        if not connection.execute(
            "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name='vector')"
        ).fetchone()[0]:  # pyright: ignore[reportOptionalSubscript]
            pytest.skip("Requires pgvector on the isolated database.")
        schema = f"knowledge_test_{uuid4().hex}"
        try:
            subprocess.run(
                [sys.executable, "-m", "alembic", "upgrade", "0013"], check=True, timeout=90,
                cwd=Path(__file__).resolve().parents[1],
                env={**os.environ, "DATABASE_URL": url, "AI_DB_SCHEMA": schema, "AI_SERVER_TOKEN": TOKEN},
            )
            yield url, schema
        finally:
            connection.execute(sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema)))


@pytest.fixture
def isolated_store(database: tuple[str, str]):
    from contextlib import asynccontextmanager

    @asynccontextmanager
    async def open_store() -> AsyncIterator[KnowledgeStore]:
        engine = create_database(*database)
        store = KnowledgeStore(engine)
        try:
            yield store
        finally:
            await engine.dispose()

    return open_store


def test_canonical_projection_and_chunks() -> None:
    wire = {
        "z": {"z": None, "a": ["标签", "Beta"], "b": False},
        "title": "中\n文", "absent": None, "documentId": 42,
    }
    reference = '{"documentId":42,"title":"中\\n文","z":{"a":["标签","Beta"],"b":false}}'
    assert projection_hash(wire) == hashlib.sha256(reference.encode()).hexdigest()
    with pytest.raises(KnowledgeValidationError):
        projection_hash({"numeric": {"weight": 1.0}})
    original = projection_chunks(1, 42, document("甲\r\n\r\n乙\n\n丙"))
    reordered = projection_chunks(1, 42, document("丙\n\n乙\n\n乙\n\n甲"))
    assert [chunk.ordinal for chunk in reordered] == list(range(4))
    assert original[1].text == "标题\n\n乙"
    assert [chunk.content_hash for chunk in reordered] == [
        original[i].content_hash for i in (2, 1, 1, 0)
    ]


def test_embedding_space_and_float32_validation() -> None:
    space = ModelRouting.embedding_space(CANDIDATE)
    assert len(space.space_key) == 32
    assert ModelRouting.embedding_space(replace(CANDIDATE, provider_revision=4, api_key=SecretStr("rotated"))) == space
    assert ModelRouting.embedding_space(replace(CANDIDATE, model_revision=3)).space_key != space.space_key
    assert ModelRouting.embedding_space(replace(CANDIDATE, config={"dimensions": 3})).space_key != space.space_key
    with pytest.raises(InvalidEmbedding):
        ModelRouting.embedding_space(replace(CANDIDATE, config={}))
    for bad in ([1.0], [0.0, 0.0], [float("nan"), 1.0], [float("inf"), 1.0], [1e-40, 0], [1e40, 0]):
        with pytest.raises(InvalidEmbedding):
            space.vector(bad)


def test_embedding_batch_matches_provider_indexes() -> None:
    async def scenario() -> None:
        indexes = [1, 0]

        def provider(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            assert body["input"] == ["first", "second"]
            return httpx.Response(200, json={
                "object": "list", "model": body["model"],
                "usage": {"prompt_tokens": 2, "total_tokens": 2},
                "data": [
                    {"object": "embedding", "index": index, "embedding": [1.0, float(index)]}
                    for index in indexes
                ],
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as http:
            routing = ModelRouting(http)
            results = await routing.embed_batch([CANDIDATE], ["first", "second"])
            assert [result.vector.to_list() for result in results] == [[1.0, 0.0], [1.0, 1.0]]
            assert all(result.space_key == routing.embedding_space(CANDIDATE).space_key for result in results)
            indexes[:] = [1, 1]
            with pytest.raises(InvalidEmbedding, match="indexes"):
                await routing.embed_batch([CANDIDATE], ["first", "second"])

    asyncio.run(scenario(), loop_factory=LOOP_FACTORY)


def test_java_url_default_and_explicit_blank(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://localhost/test")
    monkeypatch.setenv("AI_SERVER_TOKEN", TOKEN)
    monkeypatch.delenv("JAVA_BACKEND_BASE_URL", raising=False)
    assert str(Settings(_env_file=None).java_base_url) == "http://127.0.0.1:9333/"  # pyright: ignore[reportCallIssue]
    monkeypatch.setenv("JAVA_BACKEND_BASE_URL", "")
    assert Settings(_env_file=None).java_base_url is None  # pyright: ignore[reportCallIssue]


def embed_response(request: httpx.Request, batches: list[list[str]]) -> httpx.Response:
    body = json.loads(request.content)
    inputs = body["input"]
    dimensions = body["dimensions"]
    batches.append(inputs)
    return httpx.Response(200, json={
        "object": "list", "model": body["model"], "usage": {"prompt_tokens": 3, "total_tokens": 3},
        "data": [
            {"object": "embedding", "index": i, "embedding": [1.0] + [0.0] * (dimensions - 1)}
            for i in range(len(inputs))
        ],
    })


def test_reuse_retention_revocation_and_collision(isolated_store) -> None:
    async def scenario() -> None:
        batches: list[list[str]] = []
        callbacks = []
        current = CANDIDATE

        def java(request: httpx.Request) -> httpx.Response:
            assert request.headers["Authorization"] == f"Bearer {TOKEN}"
            operation = request.url.path.rsplit("/", 1)[-1]
            callbacks.append((operation, json.loads(request.content)))
            if operation == "fail":
                return httpx.Response(200, json={"status": "terminal", "attemptCount": 1})
            return httpx.Response(200, json={"status": "completed", "alreadyApplied": False})

        async def candidates() -> list[Candidate]:
            return [current]

        async with isolated_store() as store, httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: embed_response(request, batches)),
        ) as provider, httpx.AsyncClient(transport=httpx.MockTransport(java)) as backend:
            worker = KnowledgeWorker(
                KnowledgeClient(backend, "http://java", TOKEN), store, ModelRouting(provider),
                candidates, lambda: 0, threshold=4, poll_seconds=1,
            )
            first = claim(1, document("a\n\nb\n\nc"))
            await worker.handle(first)
            await worker.handle(claim(2, document("a\n\nx\n\nc")))
            await worker.handle(claim(3, document("c\n\na\n\nx\n\nx")))
            assert batches == [["标题\n\na", "标题\n\nb", "标题\n\nc"], ["标题\n\nx"]]
            assert [body["chunkCount"] for op, body in callbacks if op == "complete"] == [3, 3, 4]
            async with store.engine.connect() as session:
                assert (await session.execute(text("""
                    SELECT DISTINCT change_seq FROM knowledge_chunk
                    WHERE source_id=42 AND cleared_at IS NULL ORDER BY change_seq
                """))).scalars().all() == [2, 3]
            await worker.handle(claim(4, action=2))
            async with store.engine.connect() as session:
                assert (await session.execute(text("""
                    SELECT DISTINCT change_seq FROM knowledge_chunk
                    WHERE source_id=42 AND cleared_at IS NULL
                """))).scalars().all() == [3]
            await worker.handle(claim(5, document("c\n\na\n\nx\n\nx")))
            assert len(batches) == 2
            current = replace(current, model_revision=3, config={"dimensions": 3})
            await worker.handle(claim(6, document("c\n\na\n\nx\n\nx")))
            assert batches[-1] == ["标题\n\nc", "标题\n\na", "标题\n\nx"]
            assert [body["chunkCount"] for op, body in callbacks if op == "complete"][-1] == 4
            async with store.engine.begin() as session:
                await session.execute(text("""
                    UPDATE knowledge_chunk SET chunk_text='collision'
                    WHERE source_id=42 AND change_seq=6 AND ordinal=0
                """))
            await worker.handle(claim(7, document("c")))
            assert callbacks[-1][0] == "fail"
            assert callbacks[-1][1]["errorKind"] == 3
            assert callbacks[-1][1]["permanent"] is True
            bad_hash = claim(7, document("c")).model_copy(update={"projection_hash": "0" * 64})
            await worker.handle(bad_hash)
            assert callbacks[-1][1]["errorKind"] == 3
            await worker.handle(claim(8, action=3))
            await worker.handle(claim(8, action=3))
            async with store.engine.connect() as session:
                assert await session.scalar(text("""
                    SELECT count(*) FROM knowledge_chunk
                    WHERE source_id=42 AND chunk_text IS NOT NULL
                """)) == 0
                assert await session.scalar(text("""
                    SELECT count(*) FROM knowledge_chunk WHERE source_id=42
                """)) == 18
            assert await store.live_spaces() == set()

    asyncio.run(scenario(), loop_factory=LOOP_FACTORY)


def test_atomic_activation_races_and_reconcile(isolated_store) -> None:
    async def scenario() -> None:
        space = ModelRouting.embedding_space(CANDIDATE)
        chunks = [
            PreparedChunk(chunk, space.vector([1, 0]))
            for chunk in projection_chunks(1, 42, document("a\n\nb"))
        ]
        async with isolated_store() as store:
            first = claim(1, source_id=44)
            second = claim(2, source_id=44)
            await asyncio.gather(
                store.apply(first, space, chunks),
                store.apply(second, space, chunks),
                return_exceptions=True,
            )
            assert await store.apply(second, space, chunks) == 2
            with pytest.raises(StaleSequenceError):
                await store.apply(first, space, chunks)
            await asyncio.gather(
                store.apply(claim(3, source_id=44), space, chunks),
                store.apply(claim(4, action=2, source_id=44)),
                return_exceptions=True,
            )
            with pytest.raises(StaleSequenceError):
                await store.apply(claim(3, source_id=44), space, chunks)
            assert (await store.reconcile_source(claim(4, action=2, source_id=44), False)).matches
            assert await store.apply(claim(5, source_id=44), space, chunks) == 2
            async with store.engine.begin() as session:
                # 第二个块写入失败，状态指针和同语句首块都必须回滚。
                await session.execute(text("""
                    INSERT INTO knowledge_chunk
                      (source_type,source_id,change_seq,ordinal,content_hash,space_key,cleared_at)
                    VALUES (1,44,6,1,:digest,:space,now())
                """), {"digest": chunks[1].chunk.content_hash, "space": space.space_key})
            with pytest.raises(IntegrityError):
                await store.apply(claim(6, source_id=44), space, chunks)
            assert (await store.reconcile_source(claim(5, source_id=44), True)).matches
            async with store.engine.begin() as session:
                assert await session.scalar(text("""
                    SELECT count(*) FROM knowledge_chunk WHERE source_id=44 AND change_seq=6
                """)) == 1
                await session.execute(text("""
                    UPDATE knowledge_chunk SET chunk_text=NULL, embedding=NULL, cleared_at=now()
                    WHERE source_id=44 AND change_seq=5 AND ordinal=1
                """))
            result = await store.reconcile_source(claim(5, source_id=44), True)
            assert result.quarantined and result.local_seq == 5 and not result.matches
            assert await store.retrieve(query=EmbeddingResult(space.vector([1, 0]), space)) == []
            with pytest.raises(KnowledgeValidationError):
                await store.apply(claim(5, source_id=44), space, chunks)

    asyncio.run(scenario(), loop_factory=LOOP_FACTORY)


def test_worker_renews_while_waiting_and_withdrawal_bypasses_load(isolated_store, monkeypatch) -> None:
    assert worker_module.RENEW_INTERVAL == 40
    monkeypatch.setattr(worker_module, "RENEW_INTERVAL", 0.01)
    monkeypatch.setattr(worker_module, "YIELD_INTERVAL", 0.01)

    async def scenario() -> None:
        work = claim(1, {"displayName": "Owner", "intro": "Public profile"}, source_type=2, source_id=48)
        withdrawal = claim(2, action=3, source_type=2, source_id=48)
        batches: list[list[str]] = []
        renewed = asyncio.Event()
        active = 4
        operations = []

        def java(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            op = request.url.path.rsplit("/", 1)[-1]
            operations.append(op)
            if op == "claims":
                assert body == {"workerId": "test-worker", "limit": 1, "sourceTypes": [1, 2]}
                return httpx.Response(200, json={"claims": [work.model_dump(mode="json")]})
            assert body["sourceType"] == 2 and body["sourceId"] == 48
            if op == "renew":
                expected = {1: work, 2: withdrawal}[body["changeSeq"]]
                assert body["leaseToken"] == str(expected.lease_token)
                renewed.set()
                return httpx.Response(200, json={"leaseUntil": future_lease()})
            return httpx.Response(200, json={"status": "completed", "alreadyApplied": False})

        async def candidates() -> list[Candidate]:
            return [CANDIDATE]

        async with isolated_store() as store, httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: embed_response(request, batches)),
        ) as provider, httpx.AsyncClient(transport=httpx.MockTransport(java)) as backend:
            client = KnowledgeClient(backend, "http://java", TOKEN)
            received = await client.claim("test-worker")
            assert received == work
            worker = KnowledgeWorker(
                client, store, ModelRouting(provider), candidates, lambda: active,
                threshold=4, poll_seconds=1,
            )
            task = asyncio.create_task(worker.handle(work))
            await asyncio.wait_for(renewed.wait(), 1)
            assert batches == [] and not task.done()
            active = 0
            await asyncio.wait_for(task, 1)
            assert batches == [["Owner\n\nPublic profile"]]
            active = 4
            await asyncio.wait_for(worker.handle(withdrawal), 1)
            assert operations.count("complete") == 2
            assert await store.retrieve(profile=True) == []

    asyncio.run(scenario(), loop_factory=LOOP_FACTORY)


def test_rag_stream_authorization_and_fallback(isolated_store, caplog: pytest.LogCaptureFixture) -> None:
    assert route_question("你是谁？") == "direct"
    assert route_question("介绍一下站长") == "profile"
    assert route_question("站长的邮箱是什么") == "profile"
    assert route_question("作者介绍的 PostgreSQL 索引怎么做的") == "semantic"
    assert route_question("博主在博客里介绍过 Docker 吗") == "semantic"
    assert route_question("who is the author of this PostgreSQL article?") == "semantic"
    assert route_question("who is the author?") == "profile"
    assert route_question("站内项目的数据库设计") == "semantic"
    caplog.set_level(logging.INFO, logger="app.knowledge.rag")

    async def scenario() -> None:
        batches: list[list[str]] = []
        events: list[str] = []
        prompts: list[str] = []
        mode = "retry"
        seen = 0
        request_id = uuid4()
        space = ModelRouting.embedding_space(CANDIDATE)
        other = ModelRouting.embedding_space(replace(CANDIDATE, config={"dimensions": 3}))

        def provider(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/embeddings"):
                events.append("embed")
                return embed_response(request, batches)
            events.append("generate")
            messages = json.loads(request.content)["messages"]
            prompts.append(messages[0]["content"])
            assert messages[1]["content"] == "旧消息中的来源卡"
            frame = {
                "id": "test", "object": "chat.completion.chunk", "created": 0, "model": "chat",
                "choices": [{"index": 0, "delta": {"content": "回答"}, "finish_reason": "stop"}],
            }
            return httpx.Response(
                200, content=f"data: {json.dumps(frame)}\n\ndata: [DONE]\n\n".encode(),
                headers={"content-type": "text/event-stream"},
            )

        def java(request: httpx.Request) -> httpx.Response:
            nonlocal seen
            seen += 1
            events.append("validate")
            assert request.url.path == "/internal/v1/knowledge/auth/validate"
            assert request.extensions["timeout"]["connect"] == 1.0
            assert request.extensions["timeout"]["read"] == 1.5
            items = json.loads(request.content)["items"]
            assert len(items) == 1
            if mode == "timeout":
                raise httpx.ReadTimeout("never log this original error")
            if mode == "retry" and seen == 1:
                raise httpx.ConnectError("transient")
            item = items[0]
            assert item["sourceId"] == (52 if mode == "profile" else 50)
            return httpx.Response(200, json={"items": [{
                **item, "authorized": mode != "withdrawn",
                "currentChangeSeq": 2 if mode in {"withdrawn", "stale"} else 1,
                "title": "确认标题", "slug": "verified-slug",
            }]})

        async with isolated_store() as store, httpx.AsyncClient(
            transport=httpx.MockTransport(provider),
        ) as model_http, httpx.AsyncClient(transport=httpx.MockTransport(java)) as java_http:
            async def candidates() -> list[Candidate]:
                return [CANDIDATE]

            async def add(source_id, source_type, data, embedding_space, vector):
                await store.apply(
                    claim(1, source_type=source_type, source_id=source_id), embedding_space,
                    [PreparedChunk(chunk, embedding_space.vector(vector)) for chunk in
                     projection_chunks(source_type, source_id, data)],
                )

            await add(50, 1, {"documentId": 50, "title": "Doc", "markdown": "verified-body", "tags": []}, space, [1, 0])
            await add(51, 1, {"documentId": 51, "title": "Other", "markdown": "wrong-space", "tags": []}, other, [1, 0, 0])
            await add(52, 2, {"displayName": "Owner", "intro": "profile-body"}, other, [1, 0, 0])
            routing = ModelRouting(model_http)
            rag = KnowledgeRag(store, KnowledgeClient(java_http, "http://java", TOKEN), candidates)
            chat_candidate = replace(CANDIDATE, model_id=10, model_key="chat", config={})
            from app.workflow.chat_graph import HistoryEntry

            async def run(question: str) -> None:
                configured = ConfiguredChat(routing, [chat_candidate], knowledge=rag)
                state = ChatState(
                    execution_id=2, request_id=request_id, question=question, persona="",
                    history=[HistoryEntry(role="assistant", text="旧消息中的来源卡")],
                )
                assert [piece async for piece in configured.stream(state)] == ["回答"]

            await run("站内项目的数据库设计")
            assert events == ["embed", "validate", "validate", "generate"]
            assert all(value in prompts[-1] for value in ("verified-body", "确认标题", "verified-slug"))
            assert "wrong-space" not in prompts[-1]
            for mode in ("timeout", "withdrawn", "stale"):
                events.clear()
                await run("继续说一下数据库设计")
                assert events == ["embed", "validate", "generate"]
                assert "verified-body" not in prompts[-1]
            mode = "profile"
            events.clear()
            await run("介绍一下站长")
            assert events == ["validate", "generate"] and "profile-body" in prompts[-1]
            events.clear()
            await run("你是谁？")
            assert events == ["generate"]
            await store.apply(claim(2, action=3, source_id=50))
            events.clear()
            await run("数据库设计")
            assert events == ["generate"]
            assert str(request_id) in caplog.text
            assert all(f"stage={stage}" in caplog.text for stage in ("retrieve", "validate", "generate"))
            assert "verified-body" not in caplog.text
            assert "never log this original error" not in caplog.text

    asyncio.run(scenario(), loop_factory=LOOP_FACTORY)


def test_worker_stale_sequence_409_and_renew_retry(isolated_store, monkeypatch) -> None:
    monkeypatch.setattr(worker_module, "RENEW_INTERVAL", 0.01)
    monkeypatch.setattr(worker_module, "RENEW_RETRY_INTERVAL", 0.01)
    monkeypatch.setattr(worker_module, "RENEW_SAFETY_MARGIN", 0.05)
    monkeypatch.setattr(worker_module, "YIELD_INTERVAL", 0.01)

    async def scenario() -> None:
        batches: list[list[str]] = []
        ops: list[str] = []
        renew_calls = 0
        renew_ok = asyncio.Event()
        active = 4
        complete_409 = False
        renew_401 = False

        def java(request: httpx.Request) -> httpx.Response:
            nonlocal renew_calls
            op = request.url.path.rsplit("/", 1)[-1]
            ops.append(op)
            if op == "renew":
                renew_calls += 1
                if renew_401:
                    return httpx.Response(401, json={"code": "UNAUTHORIZED", "message": "bad token"})
                if renew_calls == 1:
                    raise httpx.ConnectError("transient renew error")
                renew_ok.set()
                return httpx.Response(200, json={"leaseUntil": future_lease()})
            if op in {"complete", "fail"} and complete_409:
                return httpx.Response(409, json={"code": "LEASE_EXPIRED_OR_SUPERSEDED", "message": "stale"})
            if op == "fail":
                return httpx.Response(200, json={"status": "retryScheduled", "attemptCount": 1})
            return httpx.Response(200, json={"status": "completed", "alreadyApplied": False})

        async def candidates() -> list[Candidate]:
            return [CANDIDATE]

        async with isolated_store() as store, httpx.AsyncClient(
            transport=httpx.MockTransport(lambda request: embed_response(request, batches)),
        ) as provider, httpx.AsyncClient(transport=httpx.MockTransport(java)) as backend:
            worker = KnowledgeWorker(
                KnowledgeClient(backend, "http://java", TOKEN), store, ModelRouting(provider),
                candidates, lambda: active, threshold=4, poll_seconds=1,
            )
            # 1) 瞬时续租失败在安全余量内重试并成功，不中止构建。
            work = claim(2, document("body-v2"))
            task = asyncio.create_task(worker.handle(work))
            await asyncio.wait_for(renew_ok.wait(), 1)
            active = 0
            await asyncio.wait_for(task, 1)
            assert renew_calls >= 2
            assert ops.count("complete") == 1

            # 2) 固定凭据 401 不可重试，首次失败即中止构建并上报 fail(4)。
            ops.clear()
            renew_calls = 0
            renew_401 = True
            active = 4
            await asyncio.wait_for(worker.handle(claim(5, document("body-v5"))), 1)
            assert renew_calls == 1
            assert [op for op in ops if op != "renew"] == ["fail"]
            renew_401 = False
            active = 0

            # 3) 过期 change_seq 不得发送 complete ACK 或 fail。
            ops.clear()
            await worker.handle(claim(1, document("body-v1")))
            assert [op for op in ops if op != "renew"] == []

            # 4) complete / fail 返回 409 时按租约失效安静返回，不抛异常。
            complete_409 = True
            ops.clear()
            await worker.handle(claim(3, document("body-v3")))
            assert [op for op in ops if op != "renew"] == ["complete"]
            ops.clear()
            bad = claim(4, document("body-v4")).model_copy(update={"projection_hash": "0" * 64})
            await worker.handle(bad)
            assert [op for op in ops if op != "renew"] == ["fail"]

    asyncio.run(scenario(), loop_factory=LOOP_FACTORY)
