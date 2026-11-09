from dataclasses import dataclass

from psycopg import AsyncConnection
from psycopg.rows import dict_row
from pydantic import Field

from app.wire import ExecutionRejected, WireModel


@dataclass(frozen=True)
class ChatPreferences:
    revision: int
    persona: str


class UpdatePreferences(WireModel):
    expected_revision: int = Field(ge=0)
    persona: str = Field(min_length=1, max_length=2000, pattern=r"\S")


class SettingsView(WireModel):
    revision: int
    persona: str
    model_provider: str
    chat_model: str
    chat_max_output_tokens: int
    embedding_model: str
    embedding_dimensions: int


async def read_preferences(connection: AsyncConnection) -> ChatPreferences:
    async with connection.cursor(row_factory=dict_row) as cursor:
        await cursor.execute(
            "SELECT revision, persona FROM chat_preferences WHERE singleton"
        )
        row = await cursor.fetchone()
    # 初始化设置是唯一生效来源，迁移不完整时拒绝使用隐式默认值。
    if row is None:
        raise RuntimeError("AI settings migration is incomplete.")
    return ChatPreferences(row["revision"], row["persona"])


async def update_preferences(
    connection: AsyncConnection,
    request: UpdatePreferences,
) -> ChatPreferences:
    # 只更新预期修订。两个后台页面并发保存时，由先提交的一方生效。
    cursor = await connection.execute(
        """
        UPDATE chat_preferences SET revision = revision + 1, persona = %s
        WHERE singleton AND revision = %s
        """,
        (request.persona, request.expected_revision),
    )
    # 旧页面须重新读取设置，避免覆盖站长已保存的修改。
    if cursor.rowcount != 1:
        raise ExecutionRejected(
            409, "REVISION_CONFLICT", "Settings changed. Reload and retry."
        )
    return await read_preferences(connection)
