import hashlib
import json
import math
from dataclasses import dataclass

from pgvector import Vector


class InvalidEmbedding(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class EmbeddingSpace:
    model_id: int
    model_revision: int
    dimensions: int
    deployment: str
    model_key: str
    normalization: str = "provider-output"
    preprocess_version: str = "knowledge-text-v1"

    @property
    def space_key(self) -> bytes:
        fields = {
            "deployment": self.deployment,
            "dimensions": self.dimensions,
            "modelId": self.model_id,
            "modelKey": self.model_key,
            "modelRevision": self.model_revision,
            "normalization": self.normalization,
            "preprocessVersion": self.preprocess_version,
        }
        canonical = json.dumps(fields, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).digest()

    def vector(self, values: list[float]) -> Vector:
        if len(values) != self.dimensions or not all(
            type(value) in (int, float) and -math.inf < value < math.inf for value in values
        ):
            raise InvalidEmbedding("Invalid embedding dimensions or values.")
        try:
            # 校验实际存入 pgvector 的 float32 值，处理转换溢出和下溢。
            vector = Vector(values)
            stored = vector.to_list()
        except (OverflowError, ValueError, TypeError):
            raise InvalidEmbedding("Embedding is not representable in pgvector.") from None
        squared_norm = sum(value * value for value in stored)
        # 余弦算子以 float32 累加平方和，避免有效范数在算子内下溢或溢出。
        if not 2**-126 <= squared_norm <= 2**127:
            raise InvalidEmbedding("Embedding norm is outside the supported range.")
        return vector


@dataclass(frozen=True, slots=True)
class EmbeddingResult:
    vector: Vector
    space: EmbeddingSpace

    @property
    def space_key(self) -> bytes:
        return self.space.space_key

    @property
    def dimensions(self) -> int:
        return self.space.dimensions

    @property
    def model_id(self) -> int:
        return self.space.model_id

    @property
    def model_revision(self) -> int:
        return self.space.model_revision

    @property
    def normalization(self) -> str:
        return self.space.normalization

    @property
    def preprocess_version(self) -> str:
        return self.space.preprocess_version
