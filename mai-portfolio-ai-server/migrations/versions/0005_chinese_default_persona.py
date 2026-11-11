import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"

OLD_DEFAULT = "Be concise and friendly."
NEW_DEFAULT = "回答简洁、友好。"


def replace_default(previous: str, current: str) -> None:
    # 仅替换初始版本的默认文本，保留站长在配置中心保存过的回答风格。
    op.get_bind().execute(
        sa.text("""
            UPDATE chat_preferences
            SET persona = :current
            WHERE singleton AND revision = 0 AND persona = :previous
        """),
        {"current": current, "previous": previous},
    )


def upgrade() -> None:
    replace_default(OLD_DEFAULT, NEW_DEFAULT)


def downgrade() -> None:
    replace_default(NEW_DEFAULT, OLD_DEFAULT)
