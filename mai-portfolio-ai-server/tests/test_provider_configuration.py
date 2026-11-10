import asyncio
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from httpx import AsyncHTTPTransport, ConnectError, Request, Response

from app.keygen import generate_secret_file
from app.provider_http import ProviderTransport, validate_provider_url


def test_secret_file_is_valid_and_cannot_be_overwritten(tmp_path: Path) -> None:
    path = generate_secret_file(tmp_path / "site-guide.env")
    original = path.read_text(encoding="utf-8")
    entries = dict(
        line.split("=", 1)
        for line in original.splitlines()
        if line and not line.startswith("#")
    )
    assert entries["AI_EXECUTION_TOKEN"] != entries["AI_MANAGEMENT_TOKEN"]
    assert len(entries["AI_EXECUTION_TOKEN"]) >= 32
    Fernet(entries["CONFIG_ENCRYPTION_KEY"])
    assert entries["AI_PAID_CALLS_ENABLED"] == "false"
    with pytest.raises(FileExistsError):
        generate_secret_file(path)
    assert path.read_text(encoding="utf-8") == original


@pytest.mark.parametrize(
    "address",
    [
        "http://api.example.com/v1",
        "https://127.0.0.1/v1",
        "https://key@api.example.com/v1",
        "https://api.example.com/v1?key=secret",
        "https://api.example.com:8443/v1",
    ],
)
def test_provider_address_requires_public_https_hostname(address: str) -> None:
    with pytest.raises(ValueError):
        validate_provider_url(address)


def test_transport_uses_checked_ip_and_original_tls_hostname(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def check() -> None:
        loop = asyncio.get_running_loop()

        async def resolve(*_args: object, **_kwargs: object) -> list[tuple]:
            return [(2, 1, 6, "", ("8.8.8.8", 443))]

        async def send(_transport: AsyncHTTPTransport, request: Request) -> Response:
            assert request.url.host == "8.8.8.8"
            assert request.headers["Host"] == "api.example.com"
            assert request.extensions["sni_hostname"] == "api.example.com"
            return Response(200)

        monkeypatch.setattr(loop, "getaddrinfo", resolve)
        monkeypatch.setattr(AsyncHTTPTransport, "handle_async_request", send)
        async with ProviderTransport() as transport:
            request = Request("POST", "https://api.example.com/v1/chat/completions")
            response = await transport.handle_async_request(request)
            assert response.status_code == 200
            assert request.url.host == "api.example.com"

            async def resolve_internal(
                *_args: object, **_kwargs: object
            ) -> list[tuple]:
                return [(2, 1, 6, "", ("10.0.0.1", 443))]

            monkeypatch.setattr(loop, "getaddrinfo", resolve_internal)
            with pytest.raises(ConnectError):
                await transport.handle_async_request(request)

    asyncio.run(check())
