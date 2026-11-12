from alembic import context
from psycopg import connect, sql
from sqlalchemy import create_engine
from sqlalchemy.pool import NullPool

from app.config import Settings

settings = Settings()  # pyright: ignore[reportCallIssue]
schema = sql.Identifier(settings.ai_db_schema)
with connect(str(settings.ai_database_url), autocommit=True) as connection:
    connection.execute(sql.SQL("CREATE SCHEMA IF NOT EXISTS {}").format(schema))

engine = create_engine(
    "postgresql+psycopg://",
    creator=lambda: connect(
        str(settings.ai_database_url), options=f"-c search_path={settings.ai_db_schema}"
    ),
    poolclass=NullPool,
)
with engine.connect() as connection:
    context.configure(connection=connection, version_table_schema=settings.ai_db_schema)
    with context.begin_transaction():
        context.run_migrations()
