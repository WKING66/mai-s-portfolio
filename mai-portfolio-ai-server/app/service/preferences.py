from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from datetime import UTC, datetime

from pydantic import SecretStr, field_validator
from pydantic_core import PydanticCustomError
from sqlalchemy import BigInteger, CheckConstraint, Text, true, update
from sqlalchemy.dialects.postgresql import JSONB
from sqlmodel import Field, col
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import (
    EDITABLE_SETTINGS,
    Settings,
    effective_settings,
    encrypt_provider_key,
)
from app.contracts.wire import ExecutionRejected, WireModel
from app.provider.provider_http import validate_provider_url
from app.storage.database import Sessions


class PersonaFields(WireModel):
    persona: str = Field(
        min_length=1, max_length=2000, schema_extra={"pattern": r"\S"}, sa_type=Text
    )


class PreferenceFields(PersonaFields):
    revision: int = Field(sa_type=BigInteger, sa_column_kwargs={"server_default": "0"})


RuntimeValue = str | int | bool


class ChatPreferences(PreferenceFields, table=True):
    __tablename__ = "chat_preferences"  # pyright: ignore[reportAssignmentType]
    __table_args__ = (CheckConstraint("singleton"),)

    singleton: bool = Field(
        default=True, primary_key=True, sa_column_kwargs={"server_default": true()}
    )

    runtime: dict[str, RuntimeValue] = Field(default_factory=dict, sa_type=JSONB)


class UpdatePreferences(PersonaFields):
    expected_revision: int = Field(ge=0)
    runtime: dict[str, RuntimeValue] | None = None
    chat_api_key: SecretStr | None = None
    embedding_api_key: SecretStr | None = None

    @field_validator("runtime")
    @classmethod
    def validate_runtime(
        cls, value: dict[str, RuntimeValue] | None
    ) -> dict[str, RuntimeValue] | None:
        if value is not None and value.keys() - EDITABLE_SETTINGS:
            raise PydanticCustomError("unknown_runtime_setting", "包含不可编辑的配置项。")
        return value

    @field_validator("chat_api_key", "embedding_api_key")
    @classmethod
    def validate_provider_key(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None:
            key = value.get_secret_value()
            # Bearer 请求头直接使用存储值，保存时拒绝会使 HTTP 请求失败的字符。
            if not 1 <= len(key) <= 2048 or not all("!" <= char <= "~" for char in key):
                raise PydanticCustomError(
                    "invalid_provider_key", "密钥须为 1 到 2048 个可见 ASCII 字符。"
                )
        return value


class SettingsView(PreferenceFields):
    model_provider: str
    chat_model: str
    chat_max_output_tokens: int
    embedding_model: str
    embedding_dimensions: int
    chat_key_configured: bool
    embedding_key_configured: bool
    runtime: dict[str, RuntimeValue]


async def read_preferences(session: AsyncSession) -> ChatPreferences:
    preferences = await session.get(ChatPreferences, True)
    # 初始化设置是唯一生效来源，迁移不完整时拒绝使用隐式默认值。
    if preferences is None:
        raise RuntimeError("AI settings migration is incomplete.")
    return preferences


async def update_preferences(
    session: AsyncSession, request: UpdatePreferences, runtime: dict[str, RuntimeValue]
) -> ChatPreferences:
    # 只更新预期修订。两个后台页面并发保存时，由先提交的一方生效。
    updated = await session.scalar(
        update(ChatPreferences)
        .where(
            col(ChatPreferences.singleton).is_(True),
            col(ChatPreferences.revision) == request.expected_revision,
        )
        .values(
            revision=col(ChatPreferences.revision) + 1,
            persona=request.persona,
            runtime=runtime,
        )
        .returning(ChatPreferences)
    )
    # 旧页面须重新读取设置，避免覆盖站长已保存的修改。
    if updated is None:
        raise ExecutionRejected(
            409, "REVISION_CONFLICT", "Settings changed. Reload and retry."
        )
    return updated


def settings_view(settings: Settings, preferences: ChatPreferences) -> SettingsView:
    effective = effective_settings(settings, preferences.runtime)
    return SettingsView(
        revision=preferences.revision,
        persona=preferences.persona,
        model_provider=effective.model_provider,
        chat_model=effective.chat_model,
        chat_max_output_tokens=effective.chat_max_output_tokens,
        embedding_model=effective.embedding_model,
        embedding_dimensions=effective.embedding_dimensions,
        chat_key_configured=bool(effective.chat_api_key.get_secret_value()),
        embedding_key_configured=bool(effective.embedding_api_key.get_secret_value()),
        runtime=effective.model_dump(mode="json", include=set(EDITABLE_SETTINGS)),
    )


async def read_settings(settings: Settings, sessions: Sessions) -> SettingsView:
    async with sessions() as session:
        return settings_view(settings, await read_preferences(session))


async def write_settings(
    settings: Settings,
    sessions: Sessions,
    body: UpdatePreferences,
    admission: AbstractAsyncContextManager[Callable[[], bool]],
) -> SettingsView:
    async with admission as has_active_executions, sessions.begin() as session:
        current = await read_preferences(session)
        return await save_settings(
            settings, session, body, current, has_active_executions()
        )


def _provider_origin(address: str) -> tuple[str, str, int] | None:
    if not address:
        return None
    url = validate_provider_url(address)
    return url.scheme, url.host, url.port or 443


async def save_settings(
    settings: Settings,
    session: AsyncSession,
    body: UpdatePreferences,
    current: ChatPreferences,
    has_active_executions: bool,
) -> SettingsView:
    supplied = body.runtime or {}
    runtime: dict[str, RuntimeValue] = current.runtime | supplied
    try:
        previous = effective_settings(settings, current.runtime)
        effective = effective_settings(settings, runtime)
        currency_changed = effective.billing_currency != previous.billing_currency
        price_version = f"manual-{datetime.now(UTC).isoformat()}"
        # 密钥只可沿用在同一来源，换路径时仍发给原主机，换主机则需要站长重新授权。
        for address, secret, model, prices in (
            (
                "chat_base_url",
                "chat_api_key",
                "chat_model",
                (
                    "chat_input_price_per_million",
                    "chat_output_price_per_million",
                ),
            ),
            (
                "embedding_base_url",
                "embedding_api_key",
                "embedding_model",
                ("embedding_input_price_per_million",),
            ),
        ):
            next_address = getattr(effective, address)
            old_address = getattr(previous, address)
            address_changed = next_address != old_address
            model_changed = getattr(effective, model) != getattr(previous, model)
            if (
                address_changed
                and next_address
                and getattr(body, secret) is None
                and _provider_origin(next_address) != _provider_origin(old_address)
            ):
                raise ExecutionRejected(
                    422,
                    "PROVIDER_KEY_REQUIRED",
                    "更换供应商地址来源时，请重新填写对应的 API 密钥。",
                )
            # 完整提交报价即确认本次价格。缺项或零价时整次拒绝，避免切换后才发现无法预占。
            if (
                next_address
                and getattr(effective, model)
                and (address_changed or model_changed or currency_changed)
            ):
                if (
                    len(effective.billing_currency) != 3
                    or not effective.billing_currency.isascii()
                    or not effective.billing_currency.isalpha()
                    or not effective.billing_currency.isupper()
                    or any(
                        price not in supplied or getattr(effective, price) <= 0
                        for price in prices
                    )
                ):
                    raise ExecutionRejected(
                        422,
                        "MODEL_PRICE_REQUIRED",
                        "请确认计价币种及受影响模型的完整正数单价。",
                    )
                if not supplied.get("price_version"):
                    runtime["price_version"] = price_version
        for field in ("chat_api_key", "embedding_api_key"):
            key = getattr(body, field)
            if key is not None:
                runtime[field] = encrypt_provider_key(settings, key)
    except ValueError as error:
        raise ExecutionRejected(
            422, "INVALID_SETTINGS", "Invalid runtime settings."
        ) from error
    # 队列固定受理时的供应商配置。Persona 单独更新不影响已有请求。
    if runtime != current.runtime and has_active_executions:
        raise ExecutionRejected(
            409, "EXECUTIONS_ACTIVE", "Wait for current executions to finish."
        )
    return settings_view(settings, await update_preferences(session, body, runtime))
