from alembic import op
from sqlalchemy import text

revision = "0012"
down_revision = "0011"

# Python str.strip() 使用的空白字符。历史回填与新正文使用同一判定。
WHITESPACE = "\t\n\v\f\r\x1c\x1d\x1e\x1f \x85\xa0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000"


def upgrade() -> None:
    op.execute("SET LOCAL lock_timeout = '3s'")
    # 降级保留诊断列及其数据，重新升级只补齐旧写入器未维护的值。
    op.execute("""
        ALTER TABLE model_execution
            ADD COLUMN IF NOT EXISTS finish_reason smallint,
            ADD COLUMN IF NOT EXISTS is_body_committed boolean,
            ADD COLUMN IF NOT EXISTS attempts jsonb
    """)
    op.execute("ALTER TABLE model_execution ALTER COLUMN is_body_committed SET DEFAULT false")
    connection = op.get_bind()
    last_id = None
    while True:
        bounds = "" if last_id is None else "WHERE id > :last_id"
        ids = connection.execute(
            text(f"SELECT id FROM model_execution {bounds} ORDER BY id LIMIT 1000"),
            {"last_id": last_id},
        ).scalars().all()
        if not ids:
            break
        connection.execute(text("""
            UPDATE model_execution
            SET finish_reason = CASE WHEN status IN (2,3,4) THEN COALESCE(finish_reason, 0)
                                     ELSE NULL END,
                is_body_committed = CASE
                    WHEN btrim(model_execution.text, :whitespace) <> '' THEN true
                    WHEN status IN (0,1) AND is_body_committed IS NULL THEN false
                    ELSE is_body_committed END
            WHERE id BETWEEN :first_id AND :last_id
              AND ((status IN (2,3,4) AND finish_reason IS NULL)
                   OR (is_body_committed IS DISTINCT FROM true
                       AND btrim(model_execution.text, :whitespace) <> '')
                   OR (status IN (0,1) AND is_body_committed IS NULL))
        """), {"first_id": ids[0], "last_id": ids[-1], "whitespace": WHITESPACE})
        last_id = ids[-1]
    op.execute("""
        ALTER TABLE model_execution ADD CONSTRAINT ck_execution_reason CHECK (
          (status IN (0,1) AND finish_reason IS NULL) OR
          (status IN (2,3,4) AND finish_reason IS NOT NULL AND
            (finish_reason = 0 OR (status = 2 AND finish_reason = 1) OR
             (status = 3 AND finish_reason IN (2,3,4,5,6,7,8,11,12)) OR
             (status = 4 AND finish_reason IN (9,10))))
        ) NOT VALID
    """)
    op.execute("""
        ALTER TABLE model_execution ADD CONSTRAINT ck_execution_body CHECK (
          (finish_reason IS DISTINCT FROM 1 AND finish_reason IS DISTINCT FROM 4)
          OR is_body_committed IS TRUE
        ) NOT VALID
    """)
    op.execute("""
        ALTER TABLE model_execution ADD CONSTRAINT ck_execution_attempts CHECK (
          attempts IS NULL OR (status IN (2,3,4) AND CASE
            WHEN jsonb_typeof(attempts) = 'array' THEN jsonb_array_length(attempts) <= 16
            ELSE false END)
        ) NOT VALID
    """)
    op.execute("ALTER TABLE model_execution VALIDATE CONSTRAINT ck_execution_reason")
    op.execute("ALTER TABLE model_execution VALIDATE CONSTRAINT ck_execution_body")
    op.execute("ALTER TABLE model_execution VALIDATE CONSTRAINT ck_execution_attempts")


def downgrade() -> None:
    op.execute("SET LOCAL lock_timeout = '3s'")
    op.execute("""
        ALTER TABLE model_execution
            DROP CONSTRAINT ck_execution_reason,
            DROP CONSTRAINT ck_execution_body,
            DROP CONSTRAINT ck_execution_attempts,
            ALTER COLUMN is_body_committed DROP DEFAULT
    """)
