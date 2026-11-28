import asyncio
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

import httpx
from pydantic import AwareDatetime, Field

from app.contracts.wire import WireModel

PositiveId = Annotated[int, Field(strict=True, gt=0, le=2**63 - 1)]
AUTH_TIMEOUT = httpx.Timeout(1.5, connect=1.0)


class SourceVersion(WireModel):
    source_type: Annotated[int, Field(strict=True, ge=1, le=2)]
    source_id: PositiveId
    change_seq: PositiveId

    @property
    def key(self) -> tuple[int, int, int]:
        return self.source_type, self.source_id, self.change_seq


class Claim(SourceVersion):
    queue_id: PositiveId
    action: Annotated[int, Field(strict=True, ge=1, le=3)]
    build_mode: Annotated[int, Field(strict=True, ge=1, le=2)]
    lease_token: UUID
    lease_until: AwareDatetime
    attempt_count: Annotated[int, Field(strict=True, ge=1, le=5)]
    projection_hash: str | None = None
    projection: dict[str, object] | None = None


class Claims(WireModel):
    claims: list[Claim] = Field(max_length=1)
    next_due_at: datetime | None = None


class Authorization(SourceVersion):
    authorized: bool = Field(strict=True)
    current_change_seq: PositiveId | None = None
    title: str | None = None
    slug: str | None = None
    visibility_level: Literal[1, 2, 3] | None = None


class Authorizations(WireModel):
    items: list[Authorization] = Field(max_length=50)


class Renewed(WireModel):
    lease_until: AwareDatetime


class Completed(WireModel):
    status: Literal["completed"]
    already_applied: bool = Field(strict=True)


class Failed(WireModel):
    status: Literal["retryScheduled", "terminal"]
    attempt_count: Annotated[int, Field(strict=True, ge=1, le=5)]
    due_at: datetime | None = None


class KnowledgeClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, token: str) -> None:
        self.http = http
        self.base_url = base_url.rstrip("/") + "/internal/v1/knowledge"
        self.headers = {"Authorization": f"Bearer {token}"}

    async def post(self, path: str, body: dict, timeout: httpx.Timeout) -> httpx.Response:
        response = await self.http.post(
            self.base_url + path, json=body, headers=self.headers, timeout=timeout,
        )
        return response.raise_for_status()

    async def claim(self, worker_id: str) -> Claim | None:
        response = await self.post(
            "/sync/claims", {"workerId": worker_id, "limit": 1, "sourceTypes": [1, 2]},
            httpx.Timeout(10, connect=1),
        )
        claims = Claims.model_validate_json(response.content).claims
        return claims[0] if claims else None

    async def renew(self, claim: Claim) -> datetime:
        body = {
            "sourceType": claim.source_type, "sourceId": claim.source_id,
            "changeSeq": claim.change_seq, "leaseToken": str(claim.lease_token),
        }
        response = await self.post(
            f"/sync/claims/{claim.queue_id}/renew", body, httpx.Timeout(5, connect=1),
        )
        return Renewed.model_validate_json(response.content).lease_until

    async def update(
        self, claim: Claim, operation: Literal["renew", "complete", "fail"], **fields: int | bool,
    ) -> None:
        body = {
            "sourceType": claim.source_type, "sourceId": claim.source_id,
            "changeSeq": claim.change_seq, "leaseToken": str(claim.lease_token), **fields,
        }
        response = await self.post(
            f"/sync/claims/{claim.queue_id}/{operation}", body, httpx.Timeout(5, connect=1),
        )
        model = {"renew": Renewed, "complete": Completed, "fail": Failed}[operation]
        model.model_validate_json(response.content)

    async def validate(self, items: list[SourceVersion]) -> list[Authorization]:
        body = {"items": [item.model_dump(mode="json") for item in items]}
        # 限制总耗时，防止对方持续发送少量字节而延长单次读取。
        async with asyncio.timeout(3.5):
            try:
                response = await self.post("/auth/validate", body, AUTH_TIMEOUT)
            except (httpx.ConnectError, httpx.ConnectTimeout):
                response = await self.post("/auth/validate", body, AUTH_TIMEOUT)
        return Authorizations.model_validate_json(response.content).items
