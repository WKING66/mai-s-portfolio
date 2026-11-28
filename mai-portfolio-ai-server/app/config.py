from cryptography.fernet import Fernet
from pydantic import (
    AliasChoices,
    AnyHttpUrl,
    Field,
    PostgresDsn,
    SecretStr,
    field_validator,
)
from pydantic_settings import (
    BaseSettings,
    EnvSettingsSource,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
)

from app.keygen import default_secret_path


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        extra="ignore",
        hide_input_in_errors=True,
        env_ignore_empty=False,
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

    execution_concurrency: int = Field(default=2, gt=0, le=32)
    execution_queue_limit: int = Field(default=8, ge=0, le=128)
    chat_timing_enabled: bool = False
    java_base_url: AnyHttpUrl | None = Field(
        default=AnyHttpUrl("http://127.0.0.1:9333"),
        validation_alias="JAVA_BACKEND_BASE_URL",
    )
    knowledge_sync_enabled: bool = False
    knowledge_sync_poll_seconds: float = Field(default=2, gt=0, le=300)
    knowledge_sync_chat_threshold: int = Field(default=4, gt=0, le=160)
    knowledge_rag_enabled: bool = False

    @classmethod
    def settings_customise_sources(
        cls, settings_cls: type[BaseSettings], init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource, dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # 仅 Java 地址的空值具有停用含义，其余配置保留原先的空值回退行为。
        for source in (env_settings, dotenv_settings):
            if isinstance(source, EnvSettingsSource):
                source.env_vars = {
                    name: value for name, value in source.env_vars.items()
                    if value != "" or name.casefold() == "java_backend_base_url"
                }
        return init_settings, env_settings, dotenv_settings, file_secret_settings

    @field_validator("java_base_url", mode="before")
    @classmethod
    def blank_java_url(cls, value: object) -> object:
        return (value.strip() or None) if isinstance(value, str) else value

    @field_validator("java_base_url")
    @classmethod
    def check_java_url(cls, value: AnyHttpUrl | None) -> AnyHttpUrl | None:
        if value is not None and (value.username or value.password or value.query or value.fragment):
            raise ValueError("Java backend URL must not contain credentials, query or fragment.")
        return value

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
