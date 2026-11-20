from alembic import op

revision = "0010"
down_revision = "0009"


def upgrade() -> None:
    # 原执行表只供迁移回填。仍有活动执行或未迁记录时，先收尾并核对原请求编号。
    op.execute("""
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM execution AS legacy
                WHERE legacy.status IN ('accepted', 'running') OR NOT EXISTS (
                    SELECT 1 FROM model_execution AS current_execution
                    WHERE current_execution.request_id = legacy.request_id
                )
            ) THEN
                RAISE EXCEPTION '旧执行仍有活动或未迁记录，无法移除旧表';
            END IF;
        END;
        $$;
    """)
    op.drop_table("execution")


def downgrade() -> None:
    raise RuntimeError("旧执行表已退役，回退需使用切换前的配套备份。")
