from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine
from sqlmodel.ext.asyncio.session import AsyncSession

Sessions = async_sessionmaker[AsyncSession]


def create_database(database_url: str, schema: str) -> AsyncEngine:
    """创建 AI 专用异步引擎，连接只解析 AI 自有 schema。"""
    return create_async_engine(
        make_url(database_url).set(drivername="postgresql+psycopg"),
        connect_args={"options": f"-c search_path={schema}"},
        pool_size=8,
        max_overflow=0,
        pool_timeout=3,
        pool_pre_ping=True,
        hide_parameters=True,
    )
