import os
import subprocess
import sys
from pathlib import Path
from uuid import uuid4

import pytest
from psycopg import Connection, sql
from psycopg.errors import CheckViolation

TEST_DATABASE = os.getenv("AI_TEST_DATABASE_URL")


@pytest.mark.skipif(not TEST_DATABASE, reason="Requires isolated PostgreSQL.")
def test_migration_backfills_history_and_preserves_diagnostics_on_rollback() -> None:
    assert TEST_DATABASE
    schema = f"execution_migration_{uuid4().hex}"
    env = {
        **os.environ,
        "DATABASE_URL": TEST_DATABASE,
        "AI_DB_SCHEMA": schema,
        "AI_SERVER_TOKEN": "migration-test-only-aaaaaaaaaaaaaaaa",
    }

    def migrate(command: str, revision: str) -> None:
        subprocess.run(
            [sys.executable, "-m", "alembic", command, revision],
            cwd=Path(__file__).resolve().parents[1], env=env, check=True, timeout=90,
        )

    with Connection.connect(TEST_DATABASE, autocommit=True) as connection:
        connection.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        connection.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
        try:
            migrate("upgrade", "0011")
            history = [
                (1, 0, "", None, 0, None, None),
                (2, 1, "\u3000\x1c", None, 0, None, None),
                (3, 2, "", "EXECUTION_TEXT_EXPIRED", 1, 1, 2),
                (4, 3, "\u00a0", None, 2, None, 1),
                (2**62 + 11, 4, "\u3000字", None, 3, None, None),
            ]
            for row_id, status, answer, error, usage, input_tokens, output_tokens in history:
                connection.execute("""
                    INSERT INTO model_execution
                        (id, request_id, status, text, error, usage_status, input_tokens,
                         output_tokens, input_hash, finished_at)
                    OVERRIDING SYSTEM VALUE
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'fixture',
                            CASE WHEN %s >= 2 THEN now() END)
                """, (row_id, uuid4(), status, answer, error, usage, input_tokens, output_tokens, status))
            migrate("upgrade", "0012")
            rows = connection.execute("""
                SELECT id, finish_reason, is_body_committed, attempts IS NULL, usage_status
                FROM model_execution ORDER BY id
            """).fetchall()
            assert rows == [
                (1, None, False, True, 0), (2, None, False, True, 0),
                (3, 0, None, True, 1), (4, 0, None, True, 2), (2**62 + 11, 0, True, True, 3),
            ]
            checks = connection.execute("""
                SELECT conname, convalidated FROM pg_constraint
                WHERE conrelid = 'model_execution'::regclass
                  AND conname IN ('ck_execution_reason', 'ck_execution_body', 'ck_execution_attempts')
                ORDER BY conname
            """).fetchall()
            assert checks == [
                ("ck_execution_attempts", True), ("ck_execution_body", True), ("ck_execution_reason", True)
            ]
            with pytest.raises(CheckViolation, match="ck_execution_attempts"):
                connection.execute("UPDATE model_execution SET attempts='[]' WHERE id=1")
            with pytest.raises(CheckViolation, match="ck_execution_body"):
                connection.execute("UPDATE model_execution SET finish_reason=1 WHERE id=3")
            with pytest.raises(CheckViolation, match="ck_execution_reason"):
                connection.execute("UPDATE model_execution SET finish_reason=NULL WHERE id=3")
            connection.execute("UPDATE model_execution SET finish_reason=10, attempts='[]' WHERE id = %s", (2**62 + 11,))
            migrate("downgrade", "0011")
            connection.execute("""
                UPDATE model_execution SET status=2, text='旧写入器正文', finished_at=now() WHERE id=1
            """)
            migrate("upgrade", "0012")
            rows = connection.execute("""
                SELECT id, finish_reason, is_body_committed, attempts
                FROM model_execution WHERE id IN (1, %s) ORDER BY id
            """, (2**62 + 11,)).fetchall()
            assert rows == [(1, 0, True, None), (2**62 + 11, 10, True, [])]
        finally:
            connection.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))
