from cryptography.fernet import Fernet
from pydantic import (
    AliasChoices,
    Field,
    PostgresDsn,
    SecretStr,
    field_validator,
)
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.keygen import default_secret_path


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

    execution_concurrency: int = Field(default=2, gt=0, le=16)
    execution_queue_limit: int = Field(default=8, ge=0, le=128)
    chat_timing_enabled: bool = False

    @field_validator("ai_server_token")
    @classmethod
    def validate_token(cls, value: SecretStr) -> SecretStr:
        if len(value.get_secret_value()) < 32:
            raise ValueError("Service credentials must contain at least 32 characters.")
        return value


def encrypt_provider_key(bootstrap: Settings, value: SecretStr) -> str:
    # 使用部署密钥加密，数据库和响应都不保存明文供应商密钥。
    return (
        Fernet(bootstrap.config_encryption_key.get_secret_value())
        .encrypt(value.get_secret_value().encode())
        .decode()
    )


def decrypt_provider_key(bootstrap: Settings, ciphertext: str) -> SecretStr:
    cipher = Fernet(bootstrap.config_encryption_key.get_secret_value())
    return SecretStr(cipher.decrypt(ciphertext).decode())
