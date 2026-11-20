from typing import Annotated

from fastapi import APIRouter, Path

from app.api.dependencies import BootstrapSettings, DatabaseSessions
from app.contracts.model_catalog import (
    CatalogView,
    ModelView,
    ModelWrite,
    ProviderView,
    ProviderWrite,
    RouteType,
    RouteView,
    RouteWrite,
)
from app.provider.model_probe import ModelProbeView, probe_model
from app.provider.wallet_reader import WalletReader, WalletsView
from app.service.model_catalog import (
    read_catalog,
    save_model,
    save_provider,
    save_route,
)
from app.service.prompt_catalog import (
    ActivateVersion,
    PromptConfig,
    PromptsView,
    PromptVersion,
    PublishVersion,
    VersionsView,
    activate_version,
    list_prompts,
    list_versions,
    publish_version,
)

router = APIRouter(prefix="/catalog")
wallet_reader = WalletReader()

CatalogId = Annotated[int, Path(gt=0)]


@router.get("")
async def catalog(sessions: DatabaseSessions) -> CatalogView:
    async with sessions() as session:
        return await read_catalog(session)


@router.get("/wallets")
async def wallets(settings: BootstrapSettings, sessions: DatabaseSessions) -> WalletsView:
    async with sessions() as session:
        return await wallet_reader.read(session, settings)


@router.post("/providers", status_code=201)
async def add_provider(
    body: ProviderWrite, settings: BootstrapSettings, sessions: DatabaseSessions
) -> ProviderView:
    async with sessions.begin() as session:
        return await save_provider(session, settings, body)


@router.put("/providers/{provider_id}")
async def update_provider(
    provider_id: CatalogId, body: ProviderWrite, settings: BootstrapSettings, sessions: DatabaseSessions
) -> ProviderView:
    async with sessions.begin() as session:
        return await save_provider(session, settings, body, provider_id)


@router.post("/models", status_code=201)
async def add_model(body: ModelWrite, sessions: DatabaseSessions) -> ModelView:
    async with sessions.begin() as session:
        return await save_model(session, body)


@router.put("/models/{model_id}")
async def update_model(model_id: CatalogId, body: ModelWrite, sessions: DatabaseSessions) -> ModelView:
    async with sessions.begin() as session:
        return await save_model(session, body, model_id)


@router.post("/models/{model_id}/test")
async def test_connection(
    model_id: CatalogId, settings: BootstrapSettings, sessions: DatabaseSessions
) -> ModelProbeView:
    async with sessions() as session:
        return await probe_model(session, settings, model_id)


@router.put("/routes/{route_type}")
async def replace_route(
    route_type: RouteType, body: RouteWrite, sessions: DatabaseSessions
) -> RouteView:
    async with sessions.begin() as session:
        return await save_route(session, route_type, body)


@router.get("/prompts")
async def prompts(sessions: DatabaseSessions) -> PromptsView:
    async with sessions() as session:
        return await list_prompts(session)


@router.get("/prompts/{prompt_id}/versions")
async def versions(prompt_id: CatalogId, sessions: DatabaseSessions) -> VersionsView:
    async with sessions() as session:
        return await list_versions(session, prompt_id)


@router.post("/prompts/{prompt_id}/versions", status_code=201)
async def publish(prompt_id: CatalogId, body: PublishVersion, sessions: DatabaseSessions) -> PromptVersion:
    # 提交成功后 FastAPI 才序列化响应，保留本次保存的字段供页面更新版本列表。
    async with sessions(expire_on_commit=False) as session, session.begin():
        return await publish_version(session, prompt_id, body)


@router.put("/prompts/{prompt_id}/active-version")
async def activate(prompt_id: CatalogId, body: ActivateVersion, sessions: DatabaseSessions) -> PromptConfig:
    async with sessions(expire_on_commit=False) as session, session.begin():
        return await activate_version(session, prompt_id, body)
