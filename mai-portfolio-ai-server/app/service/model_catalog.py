from datetime import UTC, datetime

from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import col, select
from sqlmodel.ext.asyncio.session import AsyncSession

from app.config import Settings, decrypt_provider_key, encrypt_provider_key
from app.contracts.model_catalog import (
    ApiProtocol,
    CatalogView,
    ChatParameters,
    EmbeddingParameters,
    ModelConfig,
    ModelProvider,
    ModelRoute,
    ModelType,
    ModelView,
    ModelWrite,
    ProviderView,
    ProviderWrite,
    RouteType,
    RouteView,
    RouteWrite,
)
from app.contracts.wire import ExecutionRejected
from app.provider.model_routing import Candidate
from app.provider.provider_http import validate_provider_url


async def lock_catalog(session: AsyncSession) -> None:
    # 管理员改模型和改执行链共用事务锁，避免链校验通过后模型被另一次保存停用。
    await session.scalar(select(func.pg_advisory_xact_lock(317341, 3)))


def provider_view(provider: ModelProvider) -> ProviderView:
    return ProviderView.model_validate(
        provider.model_dump(by_alias=False, exclude={"updated_at"}),
        update={"key_configured": provider.api_key is not None},
    )


def model_view(model: ModelConfig) -> ModelView:
    parameters = (
        ChatParameters.model_validate(model.config)
        if model.model_type == ModelType.CHAT
        else EmbeddingParameters.model_validate(model.config)
    )
    return ModelView.model_validate(
        model.model_dump(by_alias=False, exclude={"updated_at", "config"}),
        update={"config": parameters},
    )


async def read_catalog(session: AsyncSession) -> CatalogView:
    providers = (await session.exec(select(ModelProvider).order_by(col(ModelProvider.id)))).all()
    models = (await session.exec(select(ModelConfig).order_by(col(ModelConfig.id)))).all()
    members = (
        await session.exec(
            select(ModelRoute)
            .where(col(ModelRoute.is_removed).is_(False))
            .order_by(col(ModelRoute.position))
        )
    ).all()
    return CatalogView(
        providers=[provider_view(provider) for provider in providers],
        models=[model_view(model) for model in models],
        routes=[
            RouteView(
                type=route_type,
                model_ids=[member.model_config_id for member in members if member.type == route_type],
            )
            for route_type in RouteType
        ],
    )


def check_revision(expected: int | None, current: int) -> None:
    if expected != current:
        raise ExecutionRejected(409, "REVISION_CONFLICT", "配置已被修改，请刷新后重试。")


async def flush_catalog(session: AsyncSession) -> None:
    try:
        await session.flush()
    except IntegrityError as error:
        raise ExecutionRejected(409, "CATALOG_CONFLICT", "供应商名称或模型配置已存在。") from error


async def save_provider(
    session: AsyncSession, settings: Settings, body: ProviderWrite, provider_id: int | None = None
) -> ProviderView:
    await lock_catalog(session)
    fields = body.model_dump(by_alias=False, exclude={"api_key", "expected_revision"})
    if provider_id is None:
        provider = ModelProvider.model_validate(fields)
        session.add(provider)
    else:
        provider = await session.get(ModelProvider, provider_id)
        if provider is None:
            raise ExecutionRejected(404, "PROVIDER_NOT_FOUND", "供应商配置不存在。")
        check_revision(body.expected_revision, provider.revision)
        # 原密钥只允许继续发往原来源，管理员换地址时需明确提交该来源的密钥。
        if provider.api_key and body.api_key is None:
            old_url = validate_provider_url(provider.base_url)
            new_url = validate_provider_url(body.base_url)
            if old_url.host != new_url.host:
                raise ExecutionRejected(422, "PROVIDER_KEY_REQUIRED", "更换域名时请填写供应商密钥。")
        provider.sqlmodel_update(fields)
        provider.revision += 1
        provider.updated_at = datetime.now(UTC)
    if body.api_key is not None:
        provider.api_key = encrypt_provider_key(settings, body.api_key)
    await flush_catalog(session)
    return provider_view(provider)


def check_embedding_chain(models: list[ModelConfig]) -> None:
    if not models:
        return
    main = models[0]
    dimensions = EmbeddingParameters.model_validate(main.config).dimensions
    if dimensions is None or any(
        model.model_key != main.model_key
        or EmbeddingParameters.model_validate(model.config).dimensions != dimensions
        for model in models
    ):
        raise ExecutionRejected(
            422, "EMBEDDING_ROUTE_MISMATCH", "向量链候选的模型 ID 和有效维度必须与主配置一致。"
        )


async def route_models(session: AsyncSession, route_type: RouteType) -> list[ModelConfig]:
    result = await session.exec(
        select(ModelConfig)
        .join(ModelRoute, col(ModelRoute.model_config_id) == col(ModelConfig.id))
        .where(col(ModelRoute.type) == route_type, col(ModelRoute.is_removed).is_(False))
        .order_by(col(ModelRoute.position))
    )
    return list(result.all())


async def load_candidates(
    session: AsyncSession, settings: Settings, route_type: RouteType
) -> list[Candidate]:
    result = await session.exec(
        select(ModelConfig, ModelProvider)
        .join(ModelRoute, col(ModelRoute.model_config_id) == col(ModelConfig.id))
        .join(ModelProvider, col(ModelProvider.id) == col(ModelConfig.model_provider_id))
        .where(
            col(ModelRoute.type) == route_type,
            col(ModelRoute.is_removed).is_(False),
            col(ModelConfig.is_active).is_(True),
            col(ModelProvider.api_key).is_not(None),
        )
        .order_by(col(ModelRoute.position))
    )
    candidates = []
    for model, provider in result.all():
        assert model.id is not None and provider.api_key is not None
        candidates.append(Candidate(
            model_id=model.id,
            model_revision=model.revision,
            provider_revision=provider.revision,
            model_key=model.model_key,
            base_url=provider.base_url,
            api_key=decrypt_provider_key(settings, provider.api_key),
            config=model.config,
        ))
    return candidates


async def save_model(
    session: AsyncSession, body: ModelWrite, model_id: int | None = None
) -> ModelView:
    await lock_catalog(session)
    provider = await session.get(ModelProvider, body.model_provider_id)
    if provider is None:
        raise ExecutionRejected(404, "PROVIDER_NOT_FOUND", "供应商配置不存在。")
    fields = body.model_dump(by_alias=False, exclude={"expected_revision"})
    config = body.config.model_dump(by_alias=False, exclude_none=True)
    fields["config"] = config
    if model_id is None:
        model = ModelConfig.model_validate(fields)
        session.add(model)
    else:
        model = await session.get(ModelConfig, model_id)
        if model is None:
            raise ExecutionRejected(404, "MODEL_NOT_FOUND", "模型配置不存在。")
        check_revision(body.expected_revision, model.revision)
        chains = []
        for route_type in RouteType:
            chain = await route_models(session, route_type)
            if any(candidate.id == model_id for candidate in chain):
                chains.append((route_type, chain))
        for route_type, _ in chains:
            if not body.is_active or body.model_type != route_type.model_type:
                raise ExecutionRejected(
                    409, "MODEL_IN_ROUTE", "请先移出执行链，再停用模型或变更其能力类型。"
                )
            if provider.api_key is None:
                raise ExecutionRejected(422, "PROVIDER_KEY_REQUIRED", "请先为供应商配置密钥。")
        # 旧管理页只提交最大输出 Token。省略思考开关时沿用已保存的值，显式 null 恢复默认。
        if (
            isinstance(body.config, ChatParameters)
            and "enable_think" not in body.config.model_fields_set
            and "enable_think" in model.config
        ):
            config["enable_think"] = model.config["enable_think"]
        model.sqlmodel_update(fields)
        model.revision += 1
        model.updated_at = datetime.now(UTC)
        for route_type, chain in chains:
            if route_type is RouteType.EMBEDDING:
                check_embedding_chain(chain)
    await flush_catalog(session)
    return model_view(model)


async def save_route(session: AsyncSession, route_type: RouteType, body: RouteWrite) -> RouteView:
    await lock_catalog(session)
    members = (
        await session.exec(
            select(ModelRoute)
            .where(col(ModelRoute.type) == route_type)
            .order_by(col(ModelRoute.position))
        )
    ).all()
    if [member.model_config_id for member in members if not member.is_removed] != body.expected_model_ids:
        raise ExecutionRejected(409, "REVISION_CONFLICT", "执行链已被修改，请刷新后重试。")
    by_id = {}
    if body.model_ids:
        result = await session.exec(
            select(ModelConfig, ModelProvider)
            .join(ModelProvider, col(ModelProvider.id) == col(ModelConfig.model_provider_id))
            .where(col(ModelConfig.id).in_(body.model_ids))
        )
        by_id = {model.id: (model, provider) for model, provider in result.all()}
    if len(by_id) != len(body.model_ids):
        raise ExecutionRejected(422, "MODEL_NOT_FOUND", "执行链包含不存在的模型配置。")
    ordered = [by_id[model_id] for model_id in body.model_ids]
    if any(model.model_type != route_type.model_type for model, _ in ordered):
        raise ExecutionRejected(422, "ROUTE_TYPE_MISMATCH", "候选模型能力与执行链不匹配。")
    if any(
        provider.api_key is None or provider.api_protocol != ApiProtocol.OPENAI_COMPATIBLE
        for _, provider in ordered
    ):
        raise ExecutionRejected(422, "PROVIDER_NOT_READY", "请先配置候选模型的供应商接入。")
    if route_type is RouteType.EMBEDDING:
        check_embedding_chain([model for model, _ in ordered])

    # 先释放旧顺位，再设置新顺位。两步处在同一事务，交换位置不会撞到部分唯一索引。
    existing = {member.model_config_id: member for member in members}
    for member in members:
        member.is_removed = True
    await session.flush()
    for position, model_id in enumerate(body.model_ids, start=1):
        model, _ = by_id[model_id]
        if not model.is_active:
            model.is_active = True
            model.revision += 1
            model.updated_at = datetime.now(UTC)
        member = existing.get(model_id)
        if member is None:
            session.add(ModelRoute(type=route_type, model_config_id=model_id, position=position))
        else:
            member.is_removed = False
            member.position = position
    await session.flush()
    return RouteView(type=route_type, model_ids=body.model_ids)
