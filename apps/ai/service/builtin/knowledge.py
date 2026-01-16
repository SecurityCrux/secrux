from __future__ import annotations

from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from sqlmodel import Session

from ..database import get_session
from ..knowledge import search_knowledge_entries

router = APIRouter(prefix="/builtin/knowledge", tags=["builtin-knowledge"])


class KnowledgeToolRequest(BaseModel):
    tenantId: UUID
    query: str
    limit: int = 5
    tags: List[str] = Field(default_factory=list)


@router.post("/tools/knowledge.search:invoke")
def knowledge_search(
    payload: KnowledgeToolRequest,
    session: Session = Depends(get_session),
) -> dict:
    hits = search_knowledge_entries(
        tenant_id=payload.tenantId,
        query=payload.query,
        limit=max(1, min(payload.limit, 20)),
        tags=payload.tags,
        session=session,
    )
    if not hits:
        raise HTTPException(status_code=404, detail="No matching knowledge entries")
    return {"results": [hit.model_dump() for hit in hits]}
