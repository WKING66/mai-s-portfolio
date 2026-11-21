import asyncio
from collections.abc import Awaitable, Callable
from unittest.mock import Mock

import pytest
from httpx import AsyncClient, MockTransport, Request, Response
from pydantic import PostgresDsn, SecretStr
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import Settings
from app.contracts.model_catalog import ModelProvider
from app.provider import wallet_reader
from app.provider.wallet_reader import WalletReader


def provider(identifier: int, wallet: str | None = None, *, supported: bool = True) -> ModelProvider:
    return ModelProvider(
        id=identifier,
        title=f"Provider {identifier}",
        provider_code="deepseek",
        base_url="https://api.deepseek.com" if supported else "https://api.example.com",
        wallet_key=wallet,
        api_protocol=1,
        api_key=str(identifier),
    )


def provider_session(*providers: ModelProvider) -> AsyncSession:
    session = Mock(spec=AsyncSession)
    result = Mock()
    result.all.return_value = providers
    session.exec.return_value = result
    return session


def wallet_settings() -> Settings:
    return Settings(
        ai_database_url=PostgresDsn("postgresql://postgres@localhost/wallets_test"),
        ai_server_token=SecretStr("test-token-for-wallets-000000000000"),
    )


def mock_provider_http(
    monkeypatch: pytest.MonkeyPatch, handler: Callable[[Request], Awaitable[Response]],
) -> None:
    monkeypatch.setattr(wallet_reader, "decrypt_provider_key", lambda _, value: SecretStr(value))
    monkeypatch.setattr(
        wallet_reader, "provider_http_client", lambda: AsyncClient(transport=MockTransport(handler))
    )


def available_balance() -> Response:
    return Response(200, json={"balance_infos": [{"currency": "CNY", "total_balance": "12.34"}]})


def test_queries_three_distinct_wallets_per_batch(monkeypatch: pytest.MonkeyPatch) -> None:
    async def check() -> None:
        first_batch_started = asyncio.Event()
        started: list[int] = []
        completed: set[int] = set()

        async def respond(request: Request) -> Response:
            identifier = int(request.headers["Authorization"].removeprefix("Bearer "))
            started.append(identifier)
            if identifier in (1, 2, 3):
                if len(started) == 3:
                    first_batch_started.set()
                await first_batch_started.wait()
            else:
                assert completed == {1, 2, 3}
            completed.add(identifier)
            return available_balance()

        mock_provider_http(monkeypatch, respond)
        session = provider_session(
            provider(1, "shared"), provider(2), provider(3), provider(4, "shared"),
            provider(5), provider(6, supported=False),
        )
        result = await asyncio.wait_for(WalletReader().read(session, wallet_settings()), 1.0)

        assert started == [1, 2, 3, 5]
        assert [wallet.provider_ids for wallet in result.wallets] == [[1, 4], [2], [3], [5], [6]]
        assert [wallet.status for wallet in result.wallets] == ["available"] * 4 + ["unsupported"]

    asyncio.run(check())


def test_total_timeout_preserves_completed_and_cached_wallets(monkeypatch: pytest.MonkeyPatch) -> None:
    async def check() -> None:
        second_read = False
        cancelled: set[int] = set()
        started: list[int] = []

        async def respond(request: Request) -> Response:
            identifier = int(request.headers["Authorization"].removeprefix("Bearer "))
            if second_read:
                started.append(identifier)
                if identifier != 1:
                    try:
                        await asyncio.Event().wait()
                    finally:
                        cancelled.add(identifier)
            return available_balance()

        mock_provider_http(monkeypatch, respond)
        monkeypatch.setattr(wallet_reader, "WALLET_READ_TIMEOUT", 0.15)
        reader = WalletReader()
        settings = wallet_settings()
        cached = await reader.read(provider_session(provider(2)), settings)
        second_read = True
        result = await asyncio.wait_for(
            reader.read(provider_session(*(provider(i) for i in range(1, 5))), settings), 1.0,
        )

        assert started == [1, 2, 3]
        assert cancelled == {2, 3}
        assert [wallet.status for wallet in result.wallets] == [
            "available", "stale", "unavailable", "unavailable",
        ]
        assert result.wallets[1].balances == cached.wallets[0].balances
        assert result.wallets[1].queried_at == cached.wallets[0].queried_at

    asyncio.run(check())
