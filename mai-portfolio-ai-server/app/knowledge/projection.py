import hashlib
import json
from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, ValidationError


class KnowledgeValidationError(ValueError):
    pass


class ContentHashCollision(KnowledgeValidationError):
    pass


class DocumentProjection(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    documentId: int
    title: str
    markdown: str
    publishedAt: str | None = None
    slug: str | None = None
    summary: str | None = None
    tags: list[str]


class ProfileProjection(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    displayName: str | None = None
    headline: str | None = None
    intro: str | None = None
    githubUrl: str | None = None
    email: str | None = None


def canonical_value(value: object) -> object:
    if isinstance(value, dict):
        return {key: canonical_value(item) for key, item in value.items() if item is not None}
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return value
    if isinstance(value, (str, int)):
        return value
    raise KnowledgeValidationError("Unsupported canonical projection value.")


def projection_hash(projection: dict[str, object]) -> str:
    encoded = json.dumps(
        canonical_value(projection), sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


@dataclass(frozen=True, slots=True)
class Chunk:
    ordinal: int
    text: str
    content_hash: bytes


CHUNK_CHARACTERS = 800
TITLE_CHARACTERS = 200


def projection_chunks(source_type: int, source_id: int, projection: dict[str, object]) -> list[Chunk]:
    try:
        if source_type == 1:
            document = DocumentProjection.model_validate(projection)
            if document.documentId != source_id:
                raise KnowledgeValidationError("Projection document does not match the source.")
            title = document.title
            content = "\n\n".join(filter(None, [
                document.summary, " · ".join(document.tags), document.markdown,
            ]))
        else:
            profile = ProfileProjection.model_validate(projection)
            title = profile.displayName or ""
            content = "\n\n".join(filter(None, [
                profile.headline, profile.githubUrl, profile.email, profile.intro,
            ]))
    except ValidationError:
        raise KnowledgeValidationError("Invalid knowledge projection.") from None

    # 段落独立切块，移动段落或修改其他段落时仍能复用原向量。
    title = title.strip()[:TITLE_CHARACTERS]
    paragraphs = content.replace("\r\n", "\n").replace("\r", "\n").split("\n\n")
    texts = []
    for paragraph in paragraphs:
        paragraph = paragraph.strip()
        for offset in range(0, len(paragraph), CHUNK_CHARACTERS):
            piece = paragraph[offset:offset + CHUNK_CHARACTERS].strip()
            if piece:
                texts.append(f"{title}\n\n{piece}" if title else piece)
    if not texts and title:
        texts.append(title)
    if not texts or any("\0" in text for text in texts):
        raise KnowledgeValidationError("Empty or invalid knowledge content.")
    return [Chunk(i, text, hashlib.sha256(text.encode("utf-8")).digest()) for i, text in enumerate(texts)]
