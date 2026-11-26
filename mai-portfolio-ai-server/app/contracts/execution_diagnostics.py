from asyncio import Event
from enum import IntEnum
from time import monotonic
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class FinishReason(IntEnum):
    COMPLETED = 1
    FIRST_CHUNK_TIMEOUT = 2
    EMPTY_BODY = 3
    STREAM_INTERRUPTED = 4
    UPSTREAM_REJECTED = 5
    CAPACITY_LIMIT = 6
    CONTEXT_LIMIT = 7
    OUTPUT_LIMIT = 8
    USER_STOPPED = 9
    WORKER_LOST = 10
    UNAVAILABLE = 11
    INTERNAL_ERROR = 12


Positive = Annotated[int, Field(gt=0)]
NonNegative = Annotated[int, Field(ge=0)]
ATTEMPT_LIMIT = 16


class Attempt(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    n: Positive
    model_id: Positive
    revision: NonNegative
    outcome: Annotated[int, Field(ge=1, le=13)]
    next: Annotated[int, Field(ge=0, le=2)] = 0
    elapsed_ms: NonNegative
    first_chunk_ms: NonNegative | None = None
    first_body_ms: NonNegative | None = None
    truncated: Literal[True] | None = None
    omitted_count: Positive | None = None

    @field_validator("truncated", mode="before")
    @classmethod
    def require_boolean_flag(cls, value: object) -> object:
        if value is not None and value is not True:
            raise ValueError("Truncated must be true when present.")
        return value

    @model_validator(mode="after")
    def validate_truncation(self) -> "Attempt":
        if (self.truncated is True) != (self.omitted_count is not None):
            raise ValueError("Truncation and omitted count must appear together.")
        if self.omitted_count is not None and self.n != ATTEMPT_LIMIT + self.omitted_count:
            raise ValueError("Omitted count must match the final attempt number.")
        return self


class ExecutionDiagnostics:
    def __init__(self) -> None:
        self.dispatch_allowed = Event()
        self.dispatch_allowed.set()
        self.finish_reason: FinishReason | None = None
        self.attempts: list[Attempt] = []
        self._started_at = 0.0
        self._body_seen_ms: int | None = None
        self._first_chunk_ms: int | None = None

    def start(self, model_id: int, revision: int) -> None:
        previous_n = self.attempts[-1].n if self.attempts else 0
        self._started_at = monotonic()
        self._first_chunk_ms = None
        self._body_seen_ms = None
        attempt = Attempt(n=previous_n + 1, model_id=model_id, revision=revision, outcome=13, elapsed_ms=0)
        if len(self.attempts) == ATTEMPT_LIMIT:
            self.attempts[-1] = attempt
        else:
            self.attempts.append(attempt)

    def observe(self, visible: bool) -> None:
        elapsed_ms = self._elapsed_ms()
        if self._first_chunk_ms is None:
            self._first_chunk_ms = elapsed_ms
        if visible and self._body_seen_ms is None:
            self._body_seen_ms = elapsed_ms

    def end(self, reason: FinishReason, next_action: Literal[0, 1, 2] = 0) -> None:
        self.attempts[-1] = self._current(int(reason), next_action)

    def _elapsed_ms(self) -> int:
        return int((monotonic() - self._started_at) * 1000)

    def _current(self, outcome: int, next_action: int = 0) -> Attempt:
        latest = self.attempts[-1]
        omitted = latest.n - ATTEMPT_LIMIT
        return Attempt(
            n=latest.n, model_id=latest.model_id, revision=latest.revision,
            outcome=outcome, next=next_action, elapsed_ms=self._elapsed_ms(),
            first_chunk_ms=self._first_chunk_ms, first_body_ms=self._body_seen_ms,
            truncated=True if omitted > 0 else None, omitted_count=omitted if omitted > 0 else None,
        )

    def snapshot(self) -> list[dict[str, int | bool]]:
        saved = self.attempts
        if saved and saved[-1].outcome == 13:
            saved = [*saved[:-1], self._current(13)]
        return [attempt.model_dump(exclude_none=True) for attempt in saved]
