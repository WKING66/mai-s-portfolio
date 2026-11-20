from datetime import UTC, datetime
from decimal import Decimal
from typing import Literal

from cryptography.fernet import InvalidToken
from httpx import URL, HTTPError
from pydantic import BaseModel, ValidationError
from sqlmodel import col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import Settings, decrypt_provider_key
from app.contracts.model_catalog import ModelProvider
from app.contracts.wire import WireModel
from app.provider.provider_http import provider_http_client


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
        result = await session.exec(select(ModelProvider).order_by(col(ModelProvider.id)))
        # 分离 ORM 对象及网络请求，查余额时不占数据库连接。
        providers = list(result.all())
        for provider in providers:
            session.expunge(provider)
        await session.rollback()

        groups: dict[tuple[str, str | int], list[ModelProvider]] = {}
        for provider in providers:
            assert provider.id is not None
            identity = provider.wallet_key if provider.wallet_key is not None else provider.id
            groups.setdefault((provider.provider_code, identity), []).append(provider)

        async with provider_http_client() as http:
            wallets = []
            for group in groups.values():
                first = group[0]
                view = WalletView(
                    provider_code=first.provider_code,
                    wallet_key=first.wallet_key,
                    provider_ids=[provider.id for provider in group if provider.id is not None],
                    status="unsupported",
                    balances=[],
                )
                candidate = next(
                    (provider for provider in group
                     if URL(provider.base_url).host == "api.deepseek.com" and provider.api_key),
                    None,
                )
                if candidate is not None:
                    assert candidate.id is not None and candidate.api_key is not None
                    cache_key = (candidate.id, candidate.revision)
                    try:
                        api_key = decrypt_provider_key(settings, candidate.api_key)
                        response = await http.get(
                            "https://api.deepseek.com/user/balance",
                            headers={"Authorization": f"Bearer {api_key.get_secret_value()}"},
                            timeout=3.0,
                        )
                        response.raise_for_status()
                        payload = DeepSeekWallet.model_validate_json(response.content)
                        view.status = "available"
                        view.balances = [
                            WalletBalance(currency=balance.currency, total_balance=balance.total_balance)
                            for balance in payload.balance_infos
                        ]
                        view.queried_at = datetime.now(UTC)
                        self.last_success = {
                            key: value for key, value in self.last_success.items() if key[0] != candidate.id
                        }
                        self.last_success[cache_key] = view
                    except (HTTPError, ValidationError, ValueError, InvalidToken):
                        previous = self.last_success.get(cache_key)
                        view.status = "stale" if previous else "unavailable"
                        if previous:
                            view.balances = previous.balances
                            view.queried_at = previous.queried_at
                wallets.append(view)
        return WalletsView(wallets=wallets)
