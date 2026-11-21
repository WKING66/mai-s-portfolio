from alembic import op

revision = "0008"
down_revision = "0007"


def upgrade() -> None:
    # 旧执行必须先收尾，保留正文和幂等请求号。历史用量无可靠归属时明确标为不可得。
    op.execute("""
        DO $$ BEGIN
            IF NOT pg_try_advisory_xact_lock(317341, 2) THEN
                RAISE EXCEPTION '旧 Python 执行进程仍在运行，请先停止服务';
            END IF;
            IF EXISTS (SELECT 1 FROM execution WHERE status IN ('accepted', 'running')) THEN
                RAISE EXCEPTION '仍有旧执行运行中，请先关闭聊天并等待收尾';
            END IF;
            IF EXISTS (SELECT 1 FROM pending_budget_report) THEN
                RAISE EXCEPTION '旧预算仍有待上报记录，请先完成补报';
            END IF;
        END $$
    """)
    op.execute("""
        INSERT INTO model_execution (
            id, request_id, input_hash, status, text, error, usage_status, created_at, finished_at
        ) OVERRIDING SYSTEM VALUE
        SELECT
            (('x' || substr(replace(execution_id::text, '-', ''), 1, 16))::bit(64)::bigint
                & 4611686018427387903) | 4611686018427387904,
            request_id, input_hash,
            CASE status WHEN 'completed' THEN 2 WHEN 'failed' THEN 3 WHEN 'interrupted' THEN 4 END,
            text, error_code, 3, created_at, COALESCE(finished_at, created_at)
        FROM execution
    """)
    # 两库独立迁移：Java 用相同 UUID 映射到高位历史区间，新请求继续用低位自增序列。
    # 唯一约束阻止极罕见的历史映射冲突，迁移失败时保留两库原数据。


def downgrade() -> None:
    raise RuntimeError("执行记录切换必须使用上线前的两库配套备份回退。")
