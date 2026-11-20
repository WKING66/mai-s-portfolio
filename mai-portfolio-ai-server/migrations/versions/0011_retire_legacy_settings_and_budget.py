from alembic import op

revision = "0011"
down_revision = "0010"


def upgrade() -> None:
    # 供应商、模型、Persona 已在 0007 迁入 v3 表。旧金额门禁和待上报数据不再参与业务。
    op.drop_table("pending_budget_report")
    op.drop_table("model_attempt")
    op.drop_table("chat_preferences")


def downgrade() -> None:
    raise RuntimeError("旧配置与金额表已退役，回退需使用切换前的配套备份。")
