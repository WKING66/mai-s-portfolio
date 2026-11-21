import asyncio
from datetime import UTC, datetime
from decimal import Decimal
from typing import Literal

from cryptography.fernet import InvalidToken
from httpx import URL, AsyncClient, HTTPError
from pydantic import BaseModel, ValidationError
from sqlmodel import col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import Settings, decrypt_provider_key
from app.contracts.model_catalog import ModelProvider
from app.contracts.wire import ExecutionRejected, WireModel
from app.provider.provider_http import provider_http_client

WALLET_BATCH_SIZE = 3
WALLET_QUERY_TIMEOUT = 3.0
# Spring 的读取超时为 5 秒，预留转发与序列化时间。
WALLET_READ_TIMEOUT = 4.0


class DeepSeekBalance(BaseModel):
    currency: str
    total_balance: Decimal


class DeepSeekWallet(BaseModel):
    balance_infos: list[DeepSeekBalance]


class WalletBalance(WireModel):
    currency: str
    total_balance: Decimal


class WalletView(WireModel):
    provider_code: str
    wallet_key: str | None
    provider_ids: list[int]
    status: Literal["available", "stale", "unavailable", "unsupported"]
    balances: list[WalletBalance]
    queried_at: datetime | None = None


class WalletsView(WireModel):
    wallets: list[WalletView]


class WalletReader:
    def __init__(self) -> None:
        self.last_success: dict[tuple[int, int], WalletView] = {}

    async def read(self, session: AsyncSession, settings: Settings) -> WalletsView:
        deadline = asyncio.get_running_loop().time() + WALLET_READ_TIMEOUT
        try:
            async with asyncio.timeout_at(deadline):
                result = await session.exec(select(ModelProvider).order_by(col(ModelProvider.id)))
                # 查询钱包前释放数据库连接，外呼与排队都不持有连接。
                providers = list(result.all())
                for provider in providers:
                    session.expunge(provider)
                await session.rollback()
        except TimeoutError:
            raise ExecutionRejected(503, "AI_SETTINGS_UNAVAILABLE", "获取钱包配置超时。") from None

        groups: dict[tuple[str, str | int], list[ModelProvider]] = {}
        for provider in providers:
            assert provider.id is not None
            identity = provider.wallet_key if provider.wallet_key is not None else provider.id
            groups.setdefault((provider.provider_code, identity), []).append(provider)

        wallets: list[WalletView] = []
        queries: list[tuple[ModelProvider, WalletView]] = []
        for group in groups.values():
            first = group[0]
            view = WalletView(
                provider_code=first.provider_code,
                wallet_key=first.wallet_key,
                provider_ids=[provider.id for provider in group if provider.id is not None],
                status="unsupported",
                balances=[],
            )
            wallets.append(view)
            candidate = next(
                (provider for provider in group
                 if URL(provider.base_url).host == "api.deepseek.com" and provider.api_key),
                None,
            )
            if candidate is None:
                continue
            assert candidate.id is not None
            # 先准备降级结果，排队中的钱包超过总时限也能正常返回。
            previous = self.last_success.get((candidate.id, candidate.revision))
            view.status = "stale" if previous else "unavailable"
            if previous:
                view.balances = previous.balances
                view.queried_at = previous.queried_at
            queries.append((candidate, view))

        async with provider_http_client() as http:
            try:
                async with asyncio.timeout_at(deadline):
                    for start in range(0, len(queries), WALLET_BATCH_SIZE):
                        batch = queries[start : start + WALLET_BATCH_SIZE]
                        await asyncio.gather(
                            *(self._refresh(http, settings, candidate, view) for candidate, view in batch)
                        )
            except TimeoutError:
                # 总时限取消当批未完成的请求，已经取得的余额继续返回。
                pass
        return WalletsView(wallets=wallets)

    async def _refresh(
        self, http: AsyncClient, settings: Settings, candidate: ModelProvider, view: WalletView,
    ) -> None:
        assert candidate.id is not None and candidate.api_key is not None
        try:
            api_key = decrypt_provider_key(settings, candidate.api_key)
            async with asyncio.timeout(WALLET_QUERY_TIMEOUT):
                response = await http.get(
                    "https://api.deepseek.com/user/balance",
                    headers={"Authorization": f"Bearer {api_key.get_secret_value()}"},
                    timeout=WALLET_QUERY_TIMEOUT,
                )
            response.raise_for_status()
            payload = DeepSeekWallet.model_validate_json(response.content)
        except (TimeoutError, HTTPError, ValidationError, ValueError, InvalidToken):
            return

        view.status = "available"
        view.balances = [
            WalletBalance(currency=balance.currency, total_balance=balance.total_balance)
            for balance in payload.balance_infos
        ]
        view.queried_at = datetime.now(UTC)
        cache_key = (candidate.id, candidate.revision)
        # 慢请求返回时，保留并行刷新已经写入的更新配置。
        if any(provider_id == candidate.id and revision > candidate.revision
               for provider_id, revision in self.last_success):
            return
        self.last_success = {
            key: prior for key, prior in self.last_success.items() if key[0] != candidate.id
        }
        self.last_success[cache_key] = view
