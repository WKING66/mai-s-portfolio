from dataclasses import dataclass

from pgvector import Vector
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine

from app.knowledge.client import Claim, SourceVersion
from app.knowledge.projection import (
    Chunk,
    ContentHashCollision,
    KnowledgeValidationError,
)
from app.provider.embedding import EmbeddingResult, EmbeddingSpace

_SOURCE = "source_type = :source_type AND source_id = :source_id"
_LIVE_JOIN = """
    FROM knowledge_source_state s JOIN knowledge_chunk c
      USING (source_type, source_id, change_seq, space_key)
    WHERE s.is_active AND c.cleared_at IS NULL
"""
_CAS = text("""
    INSERT INTO knowledge_source_state AS s
      (source_type, source_id, change_seq, is_active, space_key, chunk_count)
    VALUES (:source_type, :source_id, :change_seq, :active, :space_key, :count)
    ON CONFLICT (source_type, source_id) DO UPDATE SET
      change_seq = excluded.change_seq, is_active = excluded.is_active,
      space_key = excluded.space_key, chunk_count = excluded.chunk_count, applied_at = now()
    WHERE s.change_seq < excluded.change_seq
    RETURNING change_seq
""")


class StaleSequenceError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class PreparedChunk:
    chunk: Chunk
    vector: Vector


@dataclass(frozen=True, slots=True)
class Evidence:
    source: SourceVersion
    ordinal: int
    text: str


@dataclass(frozen=True, slots=True)
class Reconciliation:
    local_seq: int | None
    java_seq: int
    matches: bool
    quarantined: bool


def _source_params(source_type: int, source_id: int) -> dict[str, int]:
    return {"source_type": source_type, "source_id": source_id}


class KnowledgeStore:
    def __init__(self, engine: AsyncEngine) -> None:
        self.engine = engine

    async def committed_count(self, claim: Claim, chunks: list[Chunk]) -> int | None:
        """校验 BUILD 是否已提交，供回执重试在路由前复用结果。"""
        async with self.engine.connect() as connection:
            return await self._committed_build_count(connection, claim, chunks)

    async def _committed_build_count(
        self, connection: AsyncConnection, claim: Claim, chunks: list[Chunk],
    ) -> int | None:
        # 同一条查询读取来源水位和切块，避免并发更新让两次读取看到不同版本。
        rows = (await connection.execute(text("""
            SELECT s.change_seq, s.is_active, s.space_key, s.chunk_count,
                   c.ordinal, c.content_hash, c.chunk_text, c.space_key AS chunk_space_key
            FROM knowledge_source_state s
            LEFT JOIN knowledge_chunk c
              ON c.source_type = s.source_type AND c.source_id = s.source_id
             AND c.change_seq = s.change_seq AND c.change_seq = :change_seq
             AND c.cleared_at IS NULL
            WHERE s.source_type = :source_type AND s.source_id = :source_id
            ORDER BY c.ordinal
        """), {
            **_source_params(claim.source_type, claim.source_id), "change_seq": claim.change_seq,
        })).all()
        if not rows or rows[0].change_seq < claim.change_seq:
            return None
        current = rows[0]
        if current.change_seq > claim.change_seq:
            raise StaleSequenceError("Stale knowledge sequence behind current state.")
        if current.is_active is not True or current.space_key is None or current.chunk_count != len(chunks):
            raise KnowledgeValidationError("Conflicting knowledge state for the same sequence.")

        # 同一序号的重试以已提交的向量空间为准，路由切换不影响回执。
        applied = [
            (row.ordinal, row.content_hash, row.chunk_text, row.chunk_space_key)
            for row in rows if row.ordinal is not None
        ]
        expected = [
            (chunk.ordinal, chunk.content_hash, chunk.text, current.space_key) for chunk in chunks
        ]
        if applied != expected:
            raise KnowledgeValidationError("Conflicting knowledge payload for the same sequence.")
        return current.chunk_count

    async def reusable(
        self, claim: Claim, space: EmbeddingSpace, chunks: dict[bytes, Chunk],
    ) -> dict[bytes, Vector]:
        async with self.engine.connect() as connection:
            rows = (await connection.execute(text(f"""
                SELECT content_hash, chunk_text, CAST(embedding AS text) AS vector
                FROM knowledge_chunk
                WHERE {_SOURCE} AND space_key = :space_key AND cleared_at IS NULL
                  AND content_hash = ANY(CAST(:hashes AS bytea[]))
                ORDER BY change_seq DESC
            """), {
                **_source_params(claim.source_type, claim.source_id),
                "space_key": space.space_key, "hashes": list(chunks),
            })).all()
        found: dict[bytes, Vector] = {}
        for content_hash, chunk_text, vector in rows:
            if chunk_text.encode("utf-8") != chunks[content_hash].text.encode("utf-8"):
                raise ContentHashCollision("Knowledge content hash collision.")
            if content_hash not in found:
                found[content_hash] = space.vector(Vector.from_text(vector).to_list())
        return found

    async def _clear(
        self, connection: AsyncConnection, source: dict[str, int], retain: int,
    ) -> None:
        # 调用者已通过 CAS 或 FOR UPDATE 持有来源状态行锁。
        await connection.execute(text(f"""
            UPDATE knowledge_chunk
            SET chunk_text = NULL, embedding = NULL, cleared_at = now()
            WHERE {_SOURCE} AND cleared_at IS NULL AND change_seq NOT IN (
                SELECT DISTINCT change_seq FROM knowledge_chunk
                WHERE {_SOURCE} AND cleared_at IS NULL ORDER BY change_seq DESC LIMIT :retain
            )
        """), {**source, "retain": retain})

    async def apply(
        self, claim: Claim, space: EmbeddingSpace | None = None,
        chunks: list[PreparedChunk] | None = None,
    ) -> int:
        active = claim.action == 1
        chunks = chunks or []
        count = len(chunks)
        if active and (space is None or not count or any(
            item.chunk.ordinal != ordinal for ordinal, item in enumerate(chunks)
        )):
            raise KnowledgeValidationError("Invalid knowledge chunk manifest.")
        source = _source_params(claim.source_type, claim.source_id)
        params = {
            **source, "change_seq": claim.change_seq, "active": active, "count": count,
            "space_key": space.space_key if space is not None else None,
        }
        async with self.engine.begin() as connection:
            if (await connection.scalar(_CAS, params)) is None:
                return await self._already_applied(connection, claim, params, chunks)
            if active:
                written = await connection.scalar(text("""
                    WITH written AS (
                        INSERT INTO knowledge_chunk
                          (source_type, source_id, change_seq, ordinal, content_hash,
                           space_key, chunk_text, embedding)
                        SELECT :source_type, :source_id, :change_seq, b.ordinal, b.content_hash,
                               :space_key, b.chunk_text, CAST(b.embedding AS public.vector)
                        FROM unnest(CAST(:ordinals AS integer[]), CAST(:hashes AS bytea[]),
                                    CAST(:texts AS text[]), CAST(:vectors AS text[]))
                             AS b(ordinal, content_hash, chunk_text, embedding)
                        RETURNING id
                    )
                    SELECT count(*) FROM written
                """), {
                    **params,
                    "ordinals": [item.chunk.ordinal for item in chunks],
                    "hashes": [item.chunk.content_hash for item in chunks],
                    "texts": [item.chunk.text for item in chunks],
                    "vectors": [item.vector.to_text() for item in chunks],
                })
                if written != count:
                    raise KnowledgeValidationError("Knowledge chunk write count mismatch.")
            await self._clear(connection, source, {1: 2, 2: 1, 3: 0}[claim.action])
        return count

    async def _already_applied(
        self, connection: AsyncConnection, claim: Claim, params: dict,
        chunks: list[PreparedChunk],
    ) -> int:
        if claim.action == 1:
            # CAS 未写入时已持有来源行锁，校验在准备向量期间提交的版本。
            count = await self._committed_build_count(connection, claim, [item.chunk for item in chunks])
            if count is None:
                raise KnowledgeValidationError("Missing committed knowledge state after sequence conflict.")
            return count

        current = (await connection.execute(text(f"""
            SELECT change_seq, is_active, space_key, chunk_count
            FROM knowledge_source_state WHERE {_SOURCE} FOR UPDATE
        """), params)).one()
        if current.change_seq > claim.change_seq:
            raise StaleSequenceError("Stale knowledge sequence behind current state.")
        if tuple(current)[1:] != (params["active"], params["space_key"], params["count"]):
            raise KnowledgeValidationError("Conflicting knowledge state for the same sequence.")
        generations = (await connection.execute(text(f"""
            SELECT DISTINCT change_seq
            FROM knowledge_chunk WHERE {_SOURCE} AND cleared_at IS NULL
        """), params)).scalars().all()
        if len(generations) > (1 if claim.action == 2 else 0):
            raise KnowledgeValidationError("Conflicting knowledge payload for the same sequence.")
        return len(chunks)

    async def live_spaces(self) -> set[bytes]:
        async with self.engine.connect() as connection:
            rows = (await connection.execute(text("""
                SELECT DISTINCT space_key FROM knowledge_source_state WHERE is_active
            """))).all()
        return {row.space_key for row in rows}

    async def retrieve(
        self, *, query: EmbeddingResult | None = None, profile: bool = False, limit: int = 6,
    ) -> list[Evidence]:
        if profile:
            statement = text(f"""
                SELECT c.source_type, c.source_id, c.change_seq, c.ordinal, c.chunk_text
                {_LIVE_JOIN} AND c.source_type = 2
                ORDER BY c.source_id, c.ordinal LIMIT :limit
            """)
            params = {"limit": limit}
        elif query is not None:
            statement = text(f"""
                WITH live AS MATERIALIZED (
                    SELECT c.source_type, c.source_id, c.change_seq, c.ordinal, c.embedding, c.chunk_text
                    {_LIVE_JOIN} AND s.space_key = :space_key
                      AND public.vector_dims(c.embedding) = :dimensions
                )
                SELECT source_type, source_id, change_seq, ordinal, chunk_text FROM live
                ORDER BY embedding OPERATOR(public.<=>) CAST(:vector AS public.vector),
                         source_type, source_id, ordinal LIMIT :limit
            """)
            params = {
                "limit": limit, "space_key": query.space_key, "dimensions": query.dimensions,
                "vector": query.vector.to_text(),
            }
        else:
            return []
        async with self.engine.connect() as connection:
            rows = (await connection.execute(statement, params)).all()
        return [
            Evidence(SourceVersion(source_type=s, source_id=i, change_seq=v), ordinal, content)
            for s, i, v, ordinal, content in rows
        ]

    async def reconcile_source(self, authoritative: SourceVersion, active: bool) -> Reconciliation:
        """Compare source watermarks and quarantine corrupt payloads under the source lock."""
        source = _source_params(authoritative.source_type, authoritative.source_id)
        async with self.engine.begin() as connection:
            # 首次看到孤块也先建立墓碑再清载荷，与同步使用同一锁顺序。
            inserted = await connection.scalar(text(f"""
                INSERT INTO knowledge_source_state
                  (source_type, source_id, change_seq, is_active, space_key, chunk_count)
                SELECT :source_type, :source_id, max(change_seq), false, NULL, 0
                FROM knowledge_chunk WHERE {_SOURCE} HAVING count(*) > 0
                ON CONFLICT (source_type, source_id) DO NOTHING RETURNING change_seq
            """), source)
            current = (await connection.execute(text(f"""
                SELECT change_seq, is_active, chunk_count, space_key
                FROM knowledge_source_state WHERE {_SOURCE} FOR UPDATE
            """), source)).one_or_none()
            if current is None:
                return Reconciliation(None, authoritative.change_seq, False, False)
            rows = (await connection.execute(text(f"""
                SELECT change_seq, ordinal, space_key FROM knowledge_chunk
                WHERE {_SOURCE} AND cleared_at IS NULL ORDER BY ordinal
            """), source)).all()
            live = [(row.ordinal, row.space_key) for row in rows if row.change_seq == current.change_seq]
            corrupt = (
                inserted is not None
                or any(row.change_seq > current.change_seq for row in rows)
                or live != [(ordinal, current.space_key) for ordinal in range(current.chunk_count)]
            )
            if corrupt:
                watermark = max([current.change_seq, *(row.change_seq for row in rows)])
                await connection.execute(text(f"""
                    UPDATE knowledge_source_state
                    SET change_seq = :watermark, is_active = false, space_key = NULL, chunk_count = 0
                    WHERE {_SOURCE}
                """), {**source, "watermark": watermark})
                await self._clear(connection, source, 0)
                return Reconciliation(watermark, authoritative.change_seq, False, True)
            matches = current.change_seq == authoritative.change_seq and current.is_active == active
            return Reconciliation(current.change_seq, authoritative.change_seq, matches, False)
