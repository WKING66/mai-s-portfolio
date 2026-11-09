from decimal import Decimal

from pydantic import Field, PostgresDsn, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        extra="ignore", hide_input_in_errors=True, env_ignore_empty=True
    )

    ai_database_url: PostgresDsn
    ai_db_schema: str = Field(default="site_guide", pattern=r"^[a-z][a-z0-9_]{0,62}$")
    ai_execution_token: SecretStr
    ai_management_token: SecretStr

    model_provider: str = ""
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

    ai_paid_calls_enabled: bool = False
    ai_daily_budget: Decimal = Field(default=Decimal(0), ge=0)
    billing_currency: str = ""
    billing_time_zone: str = "Asia/Shanghai"
    price_version: str = ""
    chat_input_price_per_million: Decimal = Field(default=Decimal(0), ge=0)
    chat_output_price_per_million: Decimal = Field(default=Decimal(0), ge=0)
    embedding_input_price_per_million: Decimal = Field(default=Decimal(0), ge=0)

    execution_concurrency: int = Field(default=2, gt=0, le=16)
    execution_queue_limit: int = Field(default=8, ge=0, le=128)

    @field_validator("ai_execution_token", "ai_management_token")
    @classmethod
    def validate_token(cls, value: SecretStr) -> SecretStr:
        if len(value.get_secret_value()) < 32:
            raise ValueError("Service credentials must contain at least 32 characters.")
        return value
