"""Add the versioned knowledge vector store."""

from alembic import op
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

revision = "0013"
down_revision = "0012"


def upgrade() -> None:
    connection = op.get_bind()
    try:
        if int(connection.scalar(text("SHOW server_version_num"))) < 160000:
            raise RuntimeError("Knowledge storage requires PostgreSQL 16 or newer.")
        # 显式定位扩展对象，AI 数据库连接的 search_path 只含自己的 schema。
        op.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public")
        supported = connection.scalar(text("""
            SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')
               AND public.vector_norm('[1,0]'::public.vector) = 1
               AND '[1,0]'::public.vector OPERATOR(public.<=>) '[1,0]'::public.vector = 0
        """))
        if not supported:
            raise RuntimeError("Required pgvector operations are unavailable.")
    except SQLAlchemyError:
        # 保持迁移水位可重试。部署可留在 0012 继续服务普通聊天。
        raise RuntimeError(
            "Install a compatible pgvector extension in public and grant migration access."
        ) from None
    op.execute("""
        CREATE TABLE knowledge_source_state (
          id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          source_type smallint NOT NULL CHECK (source_type IN (1,2)),
          source_id bigint NOT NULL CHECK (source_id > 0),
          change_seq bigint NOT NULL CHECK (change_seq > 0),
          is_active boolean NOT NULL,
          space_key bytea CHECK (octet_length(space_key) = 32),
          chunk_count integer NOT NULL CHECK (chunk_count >= 0),
          applied_at timestamptz NOT NULL DEFAULT now(),
          UNIQUE (source_type, source_id),
          CHECK ((is_active AND space_key IS NOT NULL AND chunk_count > 0) OR
                 (NOT is_active AND space_key IS NULL AND chunk_count = 0))
        )
    """)
    op.execute("""
        CREATE TABLE knowledge_chunk (
          id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          source_type smallint NOT NULL CHECK (source_type IN (1,2)),
          source_id bigint NOT NULL CHECK (source_id > 0),
          change_seq bigint NOT NULL CHECK (change_seq > 0),
          ordinal integer NOT NULL CHECK (ordinal >= 0),
          content_hash bytea NOT NULL CHECK (octet_length(content_hash) = 32),
          space_key bytea NOT NULL CHECK (octet_length(space_key) = 32),
          chunk_text text,
          embedding public.vector,
          cleared_at timestamptz,
          UNIQUE (source_type, source_id, change_seq, ordinal),
          CHECK ((cleared_at IS NULL AND chunk_text IS NOT NULL AND length(chunk_text) > 0
                  AND embedding IS NOT NULL AND public.vector_norm(embedding) > 0) OR
                 (cleared_at IS NOT NULL AND chunk_text IS NULL AND embedding IS NULL))
        )
    """)
    op.execute("""
        CREATE INDEX knowledge_chunk_reuse_idx ON knowledge_chunk
          (source_type, source_id, space_key, content_hash) WHERE cleared_at IS NULL
    """)


def downgrade() -> None:
    # 回退应用时保留水位和载荷，避免旧序号重新激活。
    raise RuntimeError("Disable knowledge features and roll back the application without downgrading 0013.")
