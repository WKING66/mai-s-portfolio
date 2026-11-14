from alembic import op

revision = "0006"
down_revision = "0005"


def upgrade() -> None:
    # Python 暂存供应商实际用量，Java 确认结算后即删除。
    op.execute("""
        CREATE TABLE pending_budget_report (
            attempt_id uuid PRIMARY KEY,
            input_tokens integer CHECK (input_tokens >= 0),
            output_tokens integer CHECK (output_tokens >= 0),
            retry_at timestamptz NOT NULL,
            CHECK ((input_tokens IS NULL) = (output_tokens IS NULL))
        )
    """)
    op.create_index("pending_budget_report_retry_idx", "pending_budget_report", ["retry_at", "attempt_id"])


def downgrade() -> None:
    # 拒绝删除仍未同步到 Java 的供应商用量，先补报或导出并对账。
    op.execute("""
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM pending_budget_report) THEN
                RAISE EXCEPTION '存在未同步的模型用量，请先对账';
            END IF;
        END $$;
    """)
    op.drop_table("pending_budget_report")
