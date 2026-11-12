import logging

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError

from app.contracts.wire import ExecutionRejected
from app.service.execution import ExecutionError

logger = logging.getLogger(__name__)


def error_response(status: int, code: str, message: str) -> JSONResponse:
    error = ExecutionError(code=code, message=message)
    return JSONResponse(error.model_dump(mode="json"), status_code=status)


async def rejected(_: Request, error: ExecutionRejected) -> JSONResponse:
    return error_response(error.status, error.code, error.message)


async def invalid(_: Request, error: RequestValidationError) -> JSONResponse:
    # 框架完成参数校验。响应只保留协议错误码，避免回显包含供应商密钥的输入。
    errors = {item["type"] for item in error.errors()}
    if errors == {"unknown_runtime_setting"}:
        return error_response(422, "INVALID_SETTINGS", "Unknown runtime setting.")
    if errors and errors <= {"unknown_runtime_setting", "invalid_provider_key"}:
        return error_response(422, "INVALID_SETTINGS", "Invalid runtime settings.")
    return error_response(422, "INVALID_INPUT", "Invalid request.")


async def unavailable(_: Request, error: SQLAlchemyError) -> JSONResponse:
    logger.warning("AI database unavailable type=%s", type(error).__name__)
    return error_response(503, "DEPENDENCY_UNAVAILABLE", "Chat storage is unavailable.")


EXCEPTION_HANDLERS = {
    ExecutionRejected: rejected,
    RequestValidationError: invalid,
    SQLAlchemyError: unavailable,
}
