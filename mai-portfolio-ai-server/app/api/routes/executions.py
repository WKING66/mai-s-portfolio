from collections.abc import AsyncIterator
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Response
from fastapi.sse import EventSourceResponse, ServerSentEvent

from app.api.dependencies import ExecutionService, require_server
from app.service.execution import Accepted, Snapshot, SubmitExecution

router = APIRouter(prefix="/executions", dependencies=[Depends(require_server)])


@router.post("", status_code=202, response_model=Accepted)
async def submit(body: SubmitExecution, executions: ExecutionService) -> Snapshot:
    # FastAPI 根据受理响应模型只输出标识与状态，正文由查询和订阅接口返回。
    return await executions.submit(body)


@router.get("")
async def find(
    request_id: Annotated[UUID, Query(alias="requestId")],
    executions: ExecutionService,
) -> Snapshot:
    return await executions.get(request_id=request_id)


@router.get("/{execution_id}")
async def get(execution_id: UUID, executions: ExecutionService) -> Snapshot:
    return await executions.get(execution_id)


async def load_subscription(
    execution_id: UUID, executions: ExecutionService, response: Response
) -> Snapshot:
    # 开流前确认执行存在并禁止缓存对话正文，查无记录仍返回普通的 404 JSON。
    snapshot = await executions.get(execution_id)
    response.headers["Cache-Control"] = "no-store"
    return snapshot


@router.get("/{execution_id}/events", response_class=EventSourceResponse)
async def events(
    snapshot: Annotated[Snapshot, Depends(load_subscription)],
    executions: ExecutionService,
) -> AsyncIterator[ServerSentEvent]:
    async for event, payload in executions.events(snapshot):
        yield ServerSentEvent(event=event, data=payload)


@router.post("/{execution_id}/stop")
async def stop(execution_id: UUID, executions: ExecutionService) -> Snapshot:
    return await executions.stop(execution_id)
