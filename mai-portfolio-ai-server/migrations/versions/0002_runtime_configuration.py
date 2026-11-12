from alembic import op

revision = "0002"
down_revision = "0001"


def upgrade() -> None:
    """为生效配置增加覆盖值，现有 Persona 和修订号原样保留。"""
    op.execute(
        "ALTER TABLE chat_preferences ADD COLUMN runtime jsonb NOT NULL DEFAULT '{}'::jsonb"
    )


def downgrade() -> None:
    op.drop_column("chat_preferences", "runtime")
