from datetime import UTC, datetime
from enum import IntEnum

from pydantic import SecretStr, field_validator, model_validator
from sqlalchemy import BigInteger, DateTime, SmallInteger, Text
from sqlalchemy.dialects.postgresql import JSONB
from sqlmodel import Field

from app.contracts.wire import WireModel
from app.provider.provider_http import validate_provider_url


class ApiProtocol(IntEnum):
    OPENAI_COMPATIBLE = 1


class ModelType(IntEnum):
    CHAT = 1
    EMBEDDING = 2


class RouteType(IntEnum):
    CHAT_REPLY = 1
    EMBEDDING = 2

    @property
    def model_type(self) -> ModelType:
        return ModelType.CHAT if self is RouteType.CHAT_REPLY else ModelType.EMBEDDING


class ChatParameters(WireModel):
    max_output_tokens: int = Field(
        default=1024, gt=0, lt=32_768, schema_extra={"strict": True}
    )

    enable_think: bool | None = Field(default=None, schema_extra={"strict": True})


class EmbeddingParameters(WireModel):
    dimensions: int | None = Field(default=None, gt=0, schema_extra={"strict": True})


class ProviderFields(WireModel):
    title: str = Field(min_length=1, max_length=100, schema_extra={"pattern": r"\S"})
    provider_code: str = Field(max_length=64, schema_extra={"pattern": r"^[a-z][a-z0-9_-]*$"})
    base_url: str = Field(sa_type=Text, max_length=2048)
    wallet_key: str | None = Field(default=None, max_length=80, schema_extra={"pattern": r"\S"})


class ProviderWrite(ProviderFields):
    api_protocol: ApiProtocol
    api_key: SecretStr | None = None
    expected_revision: int | None = Field(default=None, ge=0)

    @field_validator("base_url")
    @classmethod
    def check_url(cls, value: str) -> str:
        return str(validate_provider_url(value))

    @field_validator("api_key")
    @classmethod
    def check_key(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None:
            key = value.get_secret_value()
            if not 1 <= len(key) <= 2048 or not all("!" <= char <= "~" for char in key):
                raise ValueError("密钥须为 1 到 2048 个可见 ASCII 字符。")
        return value


class ModelProvider(ProviderFields, table=True):
    __tablename__ = "model_provider"  # pyright: ignore[reportAssignmentType]

    id: int | None = Field(default=None, primary_key=True, sa_type=BigInteger)
    api_protocol: int = Field(sa_type=SmallInteger)
    api_key: str | None = Field(default=None, sa_type=Text, exclude=True)
    revision: int = Field(default=0, sa_type=BigInteger)
    updated_at: datetime = Field(default_factory=lambda: datetime.now(UTC), sa_type=DateTime(timezone=True))


class ProviderView(ProviderFields):
    id: int
    api_protocol: ApiProtocol
    revision: int
    key_configured: bool


class ModelFields(WireModel):
    model_provider_id: int = Field(sa_type=BigInteger, gt=0)
    model_key: str = Field(min_length=1, max_length=200, schema_extra={"pattern": r"\S"})
    model_nickname: str = Field(min_length=1, max_length=100, schema_extra={"pattern": r"\S"})


class ModelWrite(ModelFields):
    model_type: ModelType
    config: ChatParameters | EmbeddingParameters
    is_active: bool = False
    expected_revision: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def check_parameters(self) -> "ModelWrite":
        expected = ChatParameters if self.model_type is ModelType.CHAT else EmbeddingParameters
        if not isinstance(self.config, expected):
            # 空对象可用于链外的 Embedding 草稿，也可采用 Chat 默认值。
            if self.model_type is ModelType.EMBEDDING and not self.config.model_fields_set:
                self.config = EmbeddingParameters()
            else:
                raise ValueError("调用参数和模型能力不匹配。")
        return self


class ModelConfig(ModelFields, table=True):
    __tablename__ = "model_config"  # pyright: ignore[reportAssignmentType]

    id: int | None = Field(default=None, primary_key=True, sa_type=BigInteger)
    model_type: int = Field(sa_type=SmallInteger)
    config: dict[str, int | bool] = Field(default_factory=dict, sa_type=JSONB)
    is_active: bool = False
    revision: int = Field(default=0, sa_type=BigInteger)
    updated_at: datetime = Field(default_factory=lambda: datetime.now(UTC), sa_type=DateTime(timezone=True))


class ModelView(ModelFields):
    id: int
    model_type: ModelType
    config: ChatParameters | EmbeddingParameters
    is_active: bool
    revision: int


class ModelRoute(WireModel, table=True):
    __tablename__ = "model_route"  # pyright: ignore[reportAssignmentType]

    id: int | None = Field(default=None, primary_key=True, sa_type=BigInteger)
    type: int = Field(sa_type=SmallInteger)
    model_config_id: int = Field(sa_type=BigInteger)
    position: int
    is_removed: bool = False


class RouteWrite(WireModel):
    expected_model_ids: list[int]
    model_ids: list[int]

    @field_validator("model_ids", "expected_model_ids")
    @classmethod
    def validate_ids(cls, values: list[int]) -> list[int]:
        if any(value <= 0 for value in values) or len(values) != len(set(values)):
            raise ValueError("候选模型编号须为不重复的正整数。")
        return values


class RouteView(WireModel):
    type: RouteType
    model_ids: list[int]


class CatalogView(WireModel):
    providers: list[ProviderView]
    models: list[ModelView]
    routes: list[RouteView]
