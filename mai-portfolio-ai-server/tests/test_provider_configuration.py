import asyncio
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from httpx import AsyncHTTPTransport, Request, Response

from app.keygen import generate_secret_file
from app.provider.provider_http import provider_http_client, validate_provider_url


def test_secret_file_is_valid_and_cannot_be_overwritten(tmp_path: Path) -> None:
    path = generate_secret_file(tmp_path / "site-guide.env")
    original = path.read_text(encoding="utf-8")
    entries = dict(
        line.split("=", 1)
        for line in original.splitlines()
        if line and not line.startswith("#")
    )
    assert set(entries) == {"DATABASE_URL", "AI_SERVER_TOKEN", "CONFIG_ENCRYPTION_KEY"}
    assert len(entries["AI_SERVER_TOKEN"]) >= 32
    Fernet(entries["CONFIG_ENCRYPTION_KEY"])
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
def test_provider_address_requires_https_hostname(address: str) -> None:
    with pytest.raises(ValueError):
        validate_provider_url(address)


def test_provider_client_leaves_hostname_resolution_to_http_transport(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def check() -> None:
        async def resolve(*_args: object, **_kwargs: object) -> list[tuple]:
            return [(2, 1, 6, "", ("198.18.0.47", 443))]

        async def send(_transport: AsyncHTTPTransport, request: Request) -> Response:
            assert request.url.host == "api.example.com"
            assert request.headers["Host"] == "api.example.com"
            return Response(200)

        monkeypatch.setattr(asyncio.get_running_loop(), "getaddrinfo", resolve)
        monkeypatch.setattr(AsyncHTTPTransport, "handle_async_request", send)
        async with provider_http_client() as client:
            response = await client.get("https://api.example.com/v1/models")
            assert response.status_code == 200

    asyncio.run(check())
