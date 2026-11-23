import logging
from dataclasses import dataclass, field
from time import monotonic, time_ns
from uuid import UUID

from openai import APIError, APIStatusError

# 和 Uvicorn 使用同一日志出口。只有显式开启诊断后才创建并记录这些计时。
logger = logging.getLogger("uvicorn.error.chat_timing")

SAFE_PROVIDER_CODES = {
    "1302",
    "AllocationQuota.FreeTierOnly",
    "Throttling.AllocationQuota",
    "Throttling.BurstRate",
    "Throttling.RateQuota",
    "insufficient_quota",
    "limit_burst_rate",
    "limit_requests",
    "rate_limit_exceeded",
}


@dataclass(slots=True)
class ChatTiming:
    request_id: UUID
    execution_id: int
    accepted_at: float = field(default_factory=monotonic)
    accepted_epoch_ms: int = field(default_factory=lambda: time_ns() // 1_000_000)
    slot_acquired_ms: int | None = None
    running_committed_ms: int | None = None
    first_body_committed_ms: int | None = None

    def elapsed_ms(self) -> int:
        return round((monotonic() - self.accepted_at) * 1000)

    def log_finished(self, cancelled: bool) -> None:
        logger.info(
            "chat_execution_timing request_id=%s execution_id=%s accepted_epoch_ms=%s slot_acquired_ms=%s "
            "running_committed_ms=%s first_body_committed_ms=%s task_finished_ms=%s cancelled=%s",
            self.request_id, self.execution_id, self.accepted_epoch_ms, self.slot_acquired_ms, self.running_committed_ms,
            self.first_body_committed_ms, self.elapsed_ms(), cancelled,
        )

    def log_candidates(self, configured: list[int], available: list[int]) -> None:
        logger.info(
            "chat_candidate_selection request_id=%s execution_id=%s configured=%s available=%s",
            self.request_id, self.execution_id, ",".join(map(str, configured)),
            ",".join(map(str, available)),
        )


@dataclass(slots=True)
class ModelAttemptTiming:
    chat: ChatTiming
    model_id: int
    attempt: int
    dispatched_ms: int = field(init=False)
    first_frame_ms: int | None = None
    first_chunk_ms: int | None = None
    first_thinking_ms: int | None = None
    first_body_ms: int | None = None
    error_type: str | None = None
    http_status: int | None = None
    provider_code: str | None = None

    def __post_init__(self) -> None:
        self.dispatched_ms = self.chat.elapsed_ms()

    def mark_frame(self) -> None:
        if self.first_frame_ms is None:
            self.first_frame_ms = self.chat.elapsed_ms()

    def mark_thinking(self) -> None:
        if self.first_thinking_ms is None:
            self.first_thinking_ms = self.chat.elapsed_ms()

    def mark_text(self, text: str) -> None:
        if self.first_body_ms is None and text.strip():
            self.first_body_ms = self.chat.elapsed_ms()

    def mark_error(self, error: BaseException) -> None:
        self.error_type = type(error).__name__
        if isinstance(error, APIStatusError):
            self.http_status = error.status_code
        if isinstance(error, APIError):
            # 只允许已知标签，避免供应商在错误码或响应中回显用户输入。
            code = str(error.code)
            if code in SAFE_PROVIDER_CODES:
                self.provider_code = code

    def log_finished(self) -> None:
        logger.info(
            "chat_model_timing request_id=%s execution_id=%s model_id=%s attempt=%s dispatched_ms=%s "
            "first_frame_ms=%s first_chunk_ms=%s first_thinking_ms=%s first_body_ms=%s "
            "finished_ms=%s error_type=%s http_status=%s provider_code=%s",
            self.chat.request_id, self.chat.execution_id, self.model_id, self.attempt, self.dispatched_ms,
            self.first_frame_ms, self.first_chunk_ms, self.first_thinking_ms, self.first_body_ms,
            self.chat.elapsed_ms(), self.error_type, self.http_status, self.provider_code,
        )
