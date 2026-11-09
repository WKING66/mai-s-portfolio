from sqlalchemy import BigInteger, CheckConstraint, Text, true, update
from sqlmodel import Field, col
from sqlmodel.ext.asyncio.session import AsyncSession

from app.wire import ExecutionRejected, WireModel


class PersonaFields(WireModel):
    persona: str = Field(
        min_length=1, max_length=2000, schema_extra={"pattern": r"\S"}, sa_type=Text
    )


class PreferenceFields(PersonaFields):
    revision: int = Field(sa_type=BigInteger, sa_column_kwargs={"server_default": "0"})


class ChatPreferences(PreferenceFields, table=True):
    __tablename__ = "chat_preferences"  # pyright: ignore[reportAssignmentType]
    __table_args__ = (CheckConstraint("singleton"),)

    singleton: bool = Field(
        default=True, primary_key=True, sa_column_kwargs={"server_default": true()}
    )


class UpdatePreferences(PersonaFields):
    expected_revision: int = Field(ge=0)


class SettingsView(PreferenceFields):
    model_provider: str
    chat_model: str
    chat_max_output_tokens: int
    embedding_model: str
    embedding_dimensions: int


async def read_preferences(session: AsyncSession) -> ChatPreferences:
    preferences = await session.get(ChatPreferences, True)
    # 初始化设置是唯一生效来源，迁移不完整时拒绝使用隐式默认值。
    if preferences is None:
        raise RuntimeError("AI settings migration is incomplete.")
    return preferences


async def update_preferences(
    session: AsyncSession, request: UpdatePreferences
) -> ChatPreferences:
    # 只更新预期修订。两个后台页面并发保存时，由先提交的一方生效。
    updated = await session.scalar(
        update(ChatPreferences)
        .where(
            col(ChatPreferences.singleton).is_(True),
            col(ChatPreferences.revision) == request.expected_revision,
        )
        .values(revision=col(ChatPreferences.revision) + 1, persona=request.persona)
        .returning(ChatPreferences)
    )
    # 旧页面须重新读取设置，避免覆盖站长已保存的修改。
    if updated is None:
        raise ExecutionRejected(
            409, "REVISION_CONFLICT", "Settings changed. Reload and retry."
        )
    return updated
