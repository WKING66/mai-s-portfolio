from pydantic.alias_generators import to_camel
from sqlmodel import SQLModel


class ExecutionRejected(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        self.status = status
        self.code = code
        self.message = message


class WireModel(SQLModel):
    model_config = {
        "alias_generator": to_camel,
        "serialize_by_alias": True,
        "validate_by_name": True,
        "extra": "forbid",
    }
