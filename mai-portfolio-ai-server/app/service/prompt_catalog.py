from datetime import UTC, datetime

from pydantic import Field as ValidationField
from sqlalchemy import BigInteger, DateTime, Text, func
from sqlmodel import Field, col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.contracts.wire import ExecutionRejected, WireModel


class PromptFields(WireModel):
    prompt_key: str = Field(max_length=64)
    description: str = Field(default="", sa_type=Text)


class PromptConfig(PromptFields, table=True):
    __tablename__ = "prompt_config"  # pyright: ignore[reportAssignmentType]

    id: int | None = Field(default=None, primary_key=True, sa_type=BigInteger)
    active_version_id: int | None = Field(default=None, sa_type=BigInteger)
    updated_at: datetime = Field(default_factory=lambda: datetime.now(UTC), sa_type=DateTime(timezone=True))


class PromptVersion(WireModel, table=True):
    __tablename__ = "prompt_version"  # pyright: ignore[reportAssignmentType]

    id: int | None = Field(default=None, primary_key=True, sa_type=BigInteger)
    prompt_config_id: int = Field(sa_type=BigInteger)
    version_no: int
    content: str = Field(sa_type=Text)
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC), sa_type=DateTime(timezone=True))


class PromptsView(WireModel):
    prompts: list[PromptConfig]


class VersionsView(WireModel):
    versions: list[PromptVersion]


class PublishVersion(WireModel):
    content: str = ValidationField(max_length=2000, pattern=r"\S")
    expected_active_version_id: int | None


class ActivateVersion(WireModel):
    version_id: int = ValidationField(gt=0)
    expected_active_version_id: int | None


async def load_prompt(session: AsyncSession, prompt_id: int, *, lock: bool = False) -> PromptConfig:
    prompt = await session.get(PromptConfig, prompt_id, with_for_update=lock)
    if prompt is None:
        raise ExecutionRejected(404, "PROMPT_NOT_FOUND", "提示词不存在。")
    return prompt


async def list_prompts(session: AsyncSession) -> PromptsView:
    rows = await session.exec(select(PromptConfig).order_by(col(PromptConfig.prompt_key)))
    return PromptsView(prompts=list(rows.all()))


async def active_persona(session: AsyncSession) -> str:
    persona = await session.scalar(
        select(col(PromptVersion.content))
        .join(PromptConfig, col(PromptConfig.active_version_id) == col(PromptVersion.id))
        .where(
            col(PromptConfig.prompt_key) == "persona",
            col(PromptVersion.prompt_config_id) == col(PromptConfig.id),
        )
    )
    if persona is None:
        raise ExecutionRejected(503, "PROMPT_UNAVAILABLE", "站长数字人的提示词尚未发布。")
    return persona


async def list_versions(session: AsyncSession, prompt_id: int) -> VersionsView:
    await load_prompt(session, prompt_id)
    rows = await session.exec(
        select(PromptVersion)
        .where(col(PromptVersion.prompt_config_id) == prompt_id)
        .order_by(col(PromptVersion.version_no).desc())
    )
    return VersionsView(versions=list(rows.all()))


def check_active_version(prompt: PromptConfig, expected_version: int | None) -> None:
    if prompt.active_version_id != expected_version:
        raise ExecutionRejected(409, "PROMPT_CONFLICT", "提示词已更新，请重新加载。")


async def publish_version(
    session: AsyncSession, prompt_id: int, body: PublishVersion
) -> PromptVersion:
    # 同一个 Prompt 的发布和回退均锁定配置行，版本号和生效指针一起提交。
    prompt = await load_prompt(session, prompt_id, lock=True)
    check_active_version(prompt, body.expected_active_version_id)
    last_version = await session.scalar(
        select(func.max(col(PromptVersion.version_no))).where(
            col(PromptVersion.prompt_config_id) == prompt_id
        )
    )
    version = PromptVersion(
        prompt_config_id=prompt_id, version_no=(last_version or 0) + 1, content=body.content
    )
    session.add(version)
    await session.flush()
    prompt.active_version_id = version.id
    prompt.updated_at = datetime.now(UTC)
    return version


async def activate_version(
    session: AsyncSession, prompt_id: int, body: ActivateVersion
) -> PromptConfig:
    prompt = await load_prompt(session, prompt_id, lock=True)
    check_active_version(prompt, body.expected_active_version_id)
    version = await session.get(PromptVersion, body.version_id)
    if version is None or version.prompt_config_id != prompt_id:
        raise ExecutionRejected(404, "PROMPT_VERSION_NOT_FOUND", "该提示词版本不存在。")
    prompt.active_version_id = version.id
    prompt.updated_at = datetime.now(UTC)
    return prompt
