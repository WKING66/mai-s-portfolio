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
        if value is not None and not 1 <= len(value.get_secret_value().strip()) <= 2048:
            raise PydanticCustomError(
                "invalid_provider_key", "供应商密钥长度必须在 1 到 2048 之间。"
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
        endpoint_changed = False
        # 地址与密钥必须成组切换，避免把旧供应商密钥发给新地址。
        for address, secret in (
            ("chat_base_url", "chat_api_key"),
            ("embedding_base_url", "embedding_api_key"),
        ):
            if address not in supplied or supplied[address] == getattr(
                previous, address
            ):
                continue
            endpoint_changed = True
            if (
                supplied[address]
                and getattr(previous, secret).get_secret_value()
                and getattr(body, secret) is None
            ):
                raise ExecutionRejected(
                    422,
                    "PROVIDER_KEY_REQUIRED",
                    "Changing a provider URL requires a new key.",
                )
        for field in ("chat_api_key", "embedding_api_key"):
            key = getattr(body, field)
            if key is not None:
                runtime[field] = encrypt_provider_key(settings, key)
        if endpoint_changed:
            # 换址时旧定价不再可信，先归零预算，待站长复核后单独保存新预算。
            runtime["ai_daily_budget"] = "0"
            runtime["ai_daily_budget_unlimited"] = False
        effective_settings(settings, runtime)
    except ValueError as error:
        raise ExecutionRejected(
            422, "INVALID_SETTINGS", "Invalid runtime settings."
        ) from error
    # 避免降低预算后，旧队列仍按旧配置开始收费。Persona 单独更新不影响已有请求。
    if runtime != current.runtime and has_active_executions:
        raise ExecutionRejected(
            409, "EXECUTIONS_ACTIVE", "Wait for current executions to finish."
        )
    return settings_view(settings, await update_preferences(session, body, runtime))
