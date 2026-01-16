from __future__ import annotations

from typing import List
from uuid import UUID

from sqlmodel import Session, select

from .models import KnowledgeEntry
from .schemas import KnowledgeSearchHit


def _score_text(text: str, tokens: List[str]) -> float:
    lowered = text.lower()
    return sum(lowered.count(token) for token in tokens)


def _build_snippet(body: str, tokens: List[str]) -> str:
    lowered = body.lower()
    for token in tokens:
        idx = lowered.find(token)
        if idx != -1:
            start = max(0, idx - 80)
            end = min(len(body), idx + 160)
            snippet = body[start:end].replace("\n", " ")
            if start > 0:
                snippet = "…" + snippet
            if end < len(body):
                snippet = snippet + "…"
            return snippet
    snippet = body[:160].replace("\n", " ")
    return snippet + ("…" if len(body) > 160 else "")


def search_knowledge_entries(
    tenant_id: UUID,
    query: str,
    limit: int,
    tags: List[str],
    session: Session,
) -> List[KnowledgeSearchHit]:
    tokens = [part.strip().lower() for part in query.split() if part.strip()]
    if not tokens:
        return []
    entries = session.exec(select(KnowledgeEntry).where(KnowledgeEntry.tenant_id == tenant_id)).all()
    filtered = []
    for entry in entries:
        if tags and not set(tags).issubset(set(entry.tags or [])):
            continue
        text = f"{entry.title} {entry.body}"
        score = _score_text(text, tokens)
        if score <= 0:
            continue
        filtered.append(
            KnowledgeSearchHit(
                entryId=entry.entry_id,
                title=entry.title,
                snippet=_build_snippet(entry.body, tokens),
                score=float(score),
                sourceUri=entry.source_uri,
                tags=entry.tags,
            )
        )
    filtered.sort(key=lambda hit: hit.score, reverse=True)
    return filtered[:limit]
