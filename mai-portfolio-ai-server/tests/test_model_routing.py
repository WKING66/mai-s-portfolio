import asyncio
import json
import logging
from dataclasses import replace
from uuid import UUID

import httpx
import pytest
from langchain_core.messages import HumanMessage
from openai import APIError
from pydantic import SecretStr, ValidationError

from app.chat_timing import ChatTiming
from app.contracts.execution_diagnostics import (
    Attempt,
    ExecutionDiagnostics,
    FinishReason,
)
from app.contracts.model_catalog import ChatParameters, ModelConfig
from app.contracts.wire import ExecutionRejected
from app.provider import model_routing
from app.provider.model_routing import MODEL_FAILURE_COOLDOWN, Candidate, ModelRouting


def candidate(model_id: int, model_key: str) -> Candidate:
    return Candidate(
        model_id=model_id,
        model_revision=0,
        provider_revision=0,
        model_key=model_key,
        base_url="https://example.com/v1",
        api_key=SecretStr("fixture-key"),
        config={"max_output_tokens": 1024},
    )


def stream_data(model: str, text: str, finish_reason: str | None = None) -> bytes:
    chunk = {
        "id": "chatcmpl-test",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": model,
        "choices": [{"index": 0, "delta": {"role": "assistant", "content": text}, "finish_reason": finish_reason}],
    }
    return f"data: {json.dumps(chunk)}\n\n".encode()


@pytest.mark.parametrize(
    ("body", "finish_signal", "expected"),
    [
        ("\u3000", "stop", FinishReason.EMPTY_BODY),
        ("partial", "length", FinishReason.OUTPUT_LIMIT),
        ("", "content_filter", FinishReason.UPSTREAM_REJECTED),
    ],
)
def test_terminal_diagnostics_follow_upstream_outcome(
    body: str, finish_signal: str, expected: FinishReason,
) -> None:
    def respond(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, content=stream_data("fixture", body, finish_signal) + b"data: [DONE]\n\n",
            headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        diagnostics = ExecutionDiagnostics()
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            with pytest.raises(ExecutionRejected):
                _ = [chunk async for chunk in ModelRouting(http).stream_chat(
                    [candidate(1, "fixture")], [HumanMessage("hello")], diagnostics=diagnostics,
                )]
        attempts = diagnostics.snapshot()
        assert diagnostics.finish_reason == expected
        assert len(attempts) == 1 and attempts[0]["outcome"] == expected
        assert ("first_body_ms" in attempts[0]) == bool(body.strip())

    asyncio.run(check())


def test_done_without_generation_chunks_falls_back() -> None:
    calls: list[str] = []

    def respond(request: httpx.Request) -> httpx.Response:
        model = json.loads(request.content)["model"]
        calls.append(model)
        body = b"" if model == "primary" else stream_data(model, "backup", "stop")
        return httpx.Response(
            200, content=body + b"data: [DONE]\n\n", headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        diagnostics = ExecutionDiagnostics()
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            received = [chunk.text async for chunk in ModelRouting(http).stream_chat(
                [candidate(1, "primary"), candidate(2, "backup")], [HumanMessage("hello")],
                diagnostics=diagnostics,
            )]
        assert calls == ["primary", "backup"]
        assert "".join(received) == "backup"
        assert diagnostics.finish_reason == FinishReason.COMPLETED
        assert [(attempt["outcome"], attempt["next"]) for attempt in diagnostics.snapshot()] == [
            (FinishReason.EMPTY_BODY, 1), (FinishReason.COMPLETED, 0),
        ]

    asyncio.run(check())


def test_first_chunk_timeout_and_retry_are_recorded_before_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(model_routing, "MODEL_RESPONSE_TIMEOUT", 0.05)
    retry_calls = 0

    async def respond(request: httpx.Request) -> httpx.Response:
        nonlocal retry_calls
        model = json.loads(request.content)["model"]
        if model == "primary":
            retry_calls += 1
            if retry_calls == 1:
                return httpx.Response(429)
            await asyncio.sleep(1)
            pytest.fail("first upstream chunk should time out")
        return httpx.Response(
            200, content=stream_data(model, "answer", "stop") + b"data: [DONE]\n\n",
            headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        diagnostics = ExecutionDiagnostics()
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            received = [chunk.text async for chunk in ModelRouting(http).stream_chat(
                [candidate(1, "primary"), candidate(2, "backup")], [HumanMessage("hello")],
                diagnostics=diagnostics,
            )]
        assert "".join(received) == "answer"
        assert diagnostics.finish_reason == FinishReason.COMPLETED
        assert [(attempt["outcome"], attempt["next"]) for attempt in diagnostics.snapshot()] == [
            (FinishReason.CAPACITY_LIMIT, 2), (FinishReason.FIRST_CHUNK_TIMEOUT, 1),
            (FinishReason.COMPLETED, 0),
        ]

    asyncio.run(check())


def test_attempt_snapshot_preserves_latest_and_rejects_non_allowlisted_fields() -> None:
    diagnostics = ExecutionDiagnostics()
    for model_id in range(1, 17):
        diagnostics.start(model_id, 0)
        diagnostics.end(FinishReason.CAPACITY_LIMIT, 1)
    exactly_sixteen = diagnostics.snapshot()
    assert len(exactly_sixteen) == 16 and "truncated" not in exactly_sixteen[-1]
    diagnostics.start(17, 4)
    overflow = diagnostics.snapshot()
    assert overflow[:15] == exactly_sixteen[:15]
    assert overflow[-1] == {
        "n": 17, "model_id": 17, "revision": 4, "next": 0, "outcome": 13,
        "elapsed_ms": overflow[-1]["elapsed_ms"], "truncated": True, "omitted_count": 1,
    }
    for field, value in (("prompt", "private"), ("model_id", "17"), ("elapsed_ms", -1),
                         ("outcome", 14), ("next", 3), ("truncated", 1)):
        with pytest.raises(ValidationError):
            Attempt.model_validate({**overflow[-1], field: value})


@pytest.mark.parametrize(
    ("host", "model_key", "config", "expected"),
    [
        ("api.siliconflow.cn", "zai-org/GLM-5.3-Flash", {"enable_think": False}, {"enable_thinking": False}),
        ("open.bigmodel.cn", "GLM-5.3-Flash", {"enable_think": False}, {"thinking": {"type": "disabled"}}),
        ("dashscope.aliyuncs.com", "qwen3.7-flash", {"enable_think": True}, {"enable_thinking": True}),
        ("dashscope.aliyuncs.com", "qwen3.7-flash", {}, {"enable_thinking": False}),
        ("api.siliconflow.cn", "zai-org/GLM-5.3-Flash", {}, {}),
    ],
)
def test_chat_sends_thinking_option_for_the_provider(host, model_key, config, expected) -> None:
    model = replace(candidate(1, model_key), base_url=f"https://{host}/v1", config=config)

    def respond(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert {key: payload[key] for key in ("enable_thinking", "thinking") if key in payload} == expected
        return httpx.Response(
            200, content=stream_data(model_key, "可用") + b"data: [DONE]\n\n",
            headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            chunks = [chunk.text async for chunk in ModelRouting(http).stream_chat([model], [HumanMessage("你好")])]
            assert "".join(chunks) == "可用"

    asyncio.run(check())


def test_thinking_option_round_trips_as_a_boolean() -> None:
    parameters = ChatParameters.model_validate({"enableThink": False})
    model = ModelConfig.model_validate({
        "model_provider_id": 1, "model_type": 1, "model_key": "fixture", "model_nickname": "fixture",
        "config": parameters.model_dump(by_alias=False, exclude_none=True),
    })
    assert model.config["enable_think"] is False
    assert ChatParameters.model_validate(model.config).model_dump()["enableThink"] is False
    with pytest.raises(ValidationError):
        ChatParameters.model_validate({"enableThink": "false"})


def test_thinking_only_signals_once_before_visible_text(caplog: pytest.LogCaptureFixture) -> None:
    trace: list[str] = []
    timing = ChatTiming(UUID("46abe276-08b6-4d59-8d3d-7822920dcfac"), 23)
    caplog.set_level(logging.INFO, logger="uvicorn.error.chat_timing")

    def respond(_: httpx.Request) -> httpx.Response:
        reasoning = {
            "id": "chatcmpl-test", "object": "chat.completion.chunk", "created": 0, "model": "fixture",
            "choices": [{"index": 0, "delta": {"role": "assistant", "reasoning_content": "private fixture"}}],
        }
        hidden = f"data: {json.dumps(reasoning)}\n\n".encode()
        return httpx.Response(
            200, content=hidden * 2 + stream_data("fixture", "正式正文") + b"data: [DONE]\n\n",
            headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            routing = ModelRouting(http)
            async for chunk in routing.stream_chat(
                [candidate(1, "fixture")], [HumanMessage("你好")],
                on_thinking=lambda: trace.append("thinking"), timing=timing,
            ):
                if chunk.text:
                    trace.append(chunk.text)
            assert trace == ["thinking", "正式正文"]

    asyncio.run(check())

    messages = [record.getMessage() for record in caplog.records if record.name == "uvicorn.error.chat_timing"]
    attempt, = [line for line in messages if line.startswith("chat_model_timing")]
    fields = dict(item.split("=", 1) for item in attempt.split()[1:])
    assert fields["request_id"] == str(timing.request_id)
    assert fields["execution_id"] == "23"
    assert (int(fields["dispatched_ms"]) <= int(fields["first_frame_ms"])
            <= int(fields["first_thinking_ms"]) <= int(fields["first_body_ms"]))
    assert "private fixture" not in attempt
    assert "正式正文" not in attempt
    assert "fixture-key" not in attempt


def test_free_quota_rejection_falls_back_to_next_model_on_same_card(caplog: pytest.LogCaptureFixture) -> None:
    called: list[str] = []
    models = [candidate(1, "primary"), candidate(2, "backup"), candidate(3, "unused")]
    timing = ChatTiming(UUID("666d32c3-1649-4b4f-8537-bdfb2fb58cf8"), 24)
    caplog.set_level(logging.INFO, logger="uvicorn.error.chat_timing")

    def respond(request: httpx.Request) -> httpx.Response:
        model = json.loads(request.content)["model"]
        called.append(model)
        if model == "primary":
            return httpx.Response(
                403, json={"error": {"code": "AllocationQuota.FreeTierOnly", "message": "test"}}
            )
        return httpx.Response(
            200,
            content=stream_data(model, "可用") + b"data: [DONE]\n\n",
            headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            routing = ModelRouting(http)
            chunks = [
                chunk.text async for chunk in routing.stream_chat(models, [HumanMessage("你好")], timing=timing)
            ]
            assert "".join(chunks) == "可用"
            assert called == ["primary", "backup"]
            assert routing.available(models) == models[1:]

    asyncio.run(check())

    attempts = [line for line in caplog.messages if line.startswith("chat_model_timing")]
    assert len(attempts) == 2
    assert "model_id=1 attempt=1" in attempts[0]
    assert "http_status=403 provider_code=AllocationQuota.FreeTierOnly" in attempts[0]
    assert "first_body_ms=None" in attempts[0]
    assert "model_id=2 attempt=1" in attempts[1]
    assert "first_body_ms=None" not in attempts[1]


def test_timing_reports_allowlisted_error_in_http_200_stream(caplog: pytest.LogCaptureFixture) -> None:
    timing = ChatTiming(UUID("0c702ac5-38bd-4a49-89ef-e38528076cae"), 25)
    caplog.set_level(logging.INFO, logger="uvicorn.error.chat_timing")

    def respond(_: httpx.Request) -> httpx.Response:
        error = {"error": {"code": "Throttling.RateQuota", "message": "private provider text"}}
        return httpx.Response(
            200, headers={"content-type": "text/event-stream"},
            content=stream_data("fixture", " ") + f"data: {json.dumps(error)}\n\n".encode(),
        )

    async def check() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            routing = ModelRouting(http)
            with pytest.raises(APIError):
                async for _ in routing.stream_chat(
                    [candidate(1, "fixture")], [HumanMessage("你好")], timing=timing,
                ):
                    pass

    asyncio.run(check())
    attempt, = [line for line in caplog.messages if line.startswith("chat_model_timing")]
    assert "first_body_ms=None" in attempt
    assert "error_type=APIError http_status=None provider_code=Throttling.RateQuota" in attempt
    assert "private provider text" not in attempt


class BrokenResponse(httpx.AsyncByteStream):
    async def __aiter__(self):
        yield stream_data("primary", "部分正文")
        raise httpx.ReadError("stream interrupted")


def test_partial_text_is_retained_without_trying_another_model() -> None:
    called: list[str] = []

    def respond(request: httpx.Request) -> httpx.Response:
        called.append(json.loads(request.content)["model"])
        return httpx.Response(
            200, stream=BrokenResponse(), headers={"content-type": "text/event-stream"}
        )

    async def check() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            routing = ModelRouting(http)
            received: list[str] = []
            with pytest.raises(ExecutionRejected) as rejected:
                async for chunk in routing.stream_chat(
                    [candidate(1, "primary"), candidate(2, "backup")], [HumanMessage("你好")]
                ):
                    received.append(chunk.text)
            assert rejected.value.code == "MODEL_STREAM_INTERRUPTED"
            assert received == ["部分正文"]
            assert called == ["primary"]

    asyncio.run(check())



@pytest.mark.parametrize("recover_by_key_change", [False, True], ids=["cooldown", "new-key"])
def test_provider_chain_recovers_primary(
    monkeypatch: pytest.MonkeyPatch, recover_by_key_change: bool
) -> None:
    primary = replace(candidate(1, "primary"), base_url="https://primary.example.test/v1")
    secondary = replace(candidate(2, "secondary"), base_url="https://secondary.example.test/v1")
    tertiary = replace(candidate(3, "tertiary"), base_url="https://tertiary.example.test/v1")
    models = [primary, secondary, tertiary]
    calls: list[tuple[str, str]] = []
    primary_healthy = False
    now = 100.0
    monkeypatch.setattr(model_routing, "monotonic", lambda: now)

    def respond(request: httpx.Request) -> httpx.Response:
        model = json.loads(request.content)["model"]
        calls.append((request.url.host, model))
        if request.url.host == "primary.example.test":
            if recover_by_key_change and request.headers["authorization"] != "Bearer replacement-key":
                return httpx.Response(401)
            if not primary_healthy:
                return httpx.Response(503)
        if request.url.host == "secondary.example.test":
            return httpx.Response(503)
        return httpx.Response(
            200,
            content=stream_data(model, model) + b"data: [DONE]\n\n",
            headers={"content-type": "text/event-stream"},
        )

    async def check() -> None:
        nonlocal now, primary_healthy
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            routing = ModelRouting(http)

            async def ask() -> str:
                chunks = [chunk.text async for chunk in routing.stream_chat(models, [HumanMessage("Hello")])]
                return "".join(chunks)

            assert await ask() == "tertiary"
            assert calls == [
                ("primary.example.test", "primary"),
                ("secondary.example.test", "secondary"),
                ("tertiary.example.test", "tertiary"),
            ]

            primary_healthy = True
            calls.clear()
            assert await ask() == "tertiary"
            assert calls == [("tertiary.example.test", "tertiary")]

            if recover_by_key_change:
                models[0] = replace(
                    primary, provider_revision=1, api_key=SecretStr("replacement-key"),
                )
            else:
                now += MODEL_FAILURE_COOLDOWN

            calls.clear()
            assert await ask() == "primary"
            assert calls == [("primary.example.test", "primary")]

    asyncio.run(check())
