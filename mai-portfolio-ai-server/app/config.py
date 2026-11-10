from decimal import Decimal
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from cryptography.fernet import Fernet, InvalidToken
from pydantic import AliasChoices, Field, PostgresDsn, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.keygen import default_secret_path
from app.provider_http import validate_provider_url


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        extra="ignore",
        hide_input_in_errors=True,
        env_ignore_empty=True,
        env_file=default_secret_path(),
        validate_by_name=True,
    )

    # 本机 Docker 和部署统一读取 DATABASE_URL，旧部署变量保留兼容。
    ai_database_url: PostgresDsn = Field(
        validation_alias=AliasChoices("DATABASE_URL", "AI_DATABASE_URL"),
    )
    ai_db_schema: str = Field(default="site_guide", pattern=r"^[a-z][a-z0-9_]{0,62}$")
    ai_server_token: SecretStr
    config_encryption_key: SecretStr = SecretStr("")

    model_provider: str = "openai-compatible"
    chat_base_url: str = ""
    chat_api_key: SecretStr = SecretStr("")
    chat_model: str = ""
    embedding_base_url: str = ""
    embedding_api_key: SecretStr = SecretStr("")
    embedding_model: str = ""
    embedding_dimensions: int = Field(default=0, ge=0)
    model_timeout_seconds: int = Field(default=45, gt=0, le=180)
    chat_max_output_tokens: int = Field(default=512, gt=0, le=8192)
    chat_billable_input_limit: int = Field(default=0, ge=0)
    embedding_billable_input_limit: int = Field(default=0, ge=0)

    ai_daily_budget: Decimal = Field(default=Decimal(0), ge=0)
    ai_daily_budget_unlimited: bool = False
    billing_currency: str = ""
    billing_time_zone: str = "Asia/Shanghai"
    price_version: str = ""
    chat_input_price_per_million: Decimal = Field(default=Decimal(0), ge=0)
    chat_output_price_per_million: Decimal = Field(default=Decimal(0), ge=0)
    embedding_input_price_per_million: Decimal = Field(default=Decimal(0), ge=0)

    execution_concurrency: int = Field(default=2, gt=0, le=16)
    execution_queue_limit: int = Field(default=8, ge=0, le=128)

    @field_validator("ai_server_token")
    @classmethod
    def validate_token(cls, value: SecretStr) -> SecretStr:
        if len(value.get_secret_value()) < 32:
            raise ValueError("Service credentials must contain at least 32 characters.")
        return value


# 数据库、服务令牌和加密主密钥用于启动信任边界，供应商参数由配置中心维护。
EDITABLE_SETTINGS = frozenset(
    {
        "chat_base_url",
        "chat_model",
        "embedding_base_url",
        "embedding_model",
        "embedding_dimensions",
        "model_timeout_seconds",
        "chat_max_output_tokens",
        "chat_billable_input_limit",
        "embedding_billable_input_limit",
        "ai_daily_budget",
        "ai_daily_budget_unlimited",
        "billing_currency",
        "billing_time_zone",
        "price_version",
        "chat_input_price_per_million",
        "chat_output_price_per_million",
        "embedding_input_price_per_million",
    }
)


STORED_SECRET_FIELDS = ("chat_api_key", "embedding_api_key")


def encrypt_provider_key(bootstrap: Settings, value: SecretStr) -> str:
    # 使用部署密钥加密，数据库和响应都不保存明文供应商密钥。
    return (
        Fernet(bootstrap.config_encryption_key.get_secret_value())
        .encrypt(value.get_secret_value().encode())
        .decode()
    )


def effective_settings(bootstrap: Settings, overrides: dict[str, Any]) -> Settings:
    """应用数据库覆盖值，缺少覆盖值时保留启动设置。"""
    if overrides.keys() - EDITABLE_SETTINGS - set(STORED_SECRET_FIELDS):
        raise ValueError("Unsupported runtime setting.")
    values = bootstrap.model_dump() | overrides
    try:
        for field in STORED_SECRET_FIELDS:
            if field in overrides:
                cipher = Fernet(bootstrap.config_encryption_key.get_secret_value())
                values[field] = cipher.decrypt(overrides[field]).decode()
    except (ValueError, InvalidToken) as error:
        raise ValueError("Stored model credentials cannot be decrypted.") from error
    effective = Settings.model_validate(values)
    for address in (effective.chat_base_url, effective.embedding_base_url):
        if address:
            validate_provider_url(address)
    try:
        ZoneInfo(effective.billing_time_zone)
    except (ValueError, ZoneInfoNotFoundError) as error:
        raise ValueError("Invalid billing time zone.") from error
    return effective
