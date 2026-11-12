from alembic import op

revision = "0004"
down_revision = "0003"


def upgrade() -> None:
    # 定时任务只扫描尚未清理的终态正文，避免已清理记录拖慢每轮扫描。
    op.execute("""
        CREATE INDEX execution_pending_cleanup_idx ON execution (finished_at, execution_id)
        WHERE finished_at IS NOT NULL AND text <> ''
    """)


def downgrade() -> None:
    op.drop_index("execution_pending_cleanup_idx", table_name="execution")
