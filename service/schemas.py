from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from .models import AiAgent, AiJob, AiMcp, KnowledgeEntry


class AiMcpRequest(BaseModel):
    name: str
    type: str
    endpoint: Optional[str] = None
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True


class AiMcpResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    profileId: UUID
    name: str
    type: str
    endpoint: Optional[str] = None
    entrypoint: Optional[str] = None
    params: Dict[str, Any]
    enabled: bool
    createdAt: datetime
    updatedAt: datetime


class AiAgentRequest(BaseModel):
    name: str
    kind: str
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict)
    stageTypes: List[str] = Field(default_factory=list)
    mcpProfileId: Optional[UUID] = None
    enabled: bool = True


class AiAgentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    agentId: UUID
    name: str
    kind: str
    entrypoint: Optional[str] = None
    params: Dict[str, Any]
    stageTypes: List[str]
    mcpProfileId: Optional[UUID] = None
    enabled: bool
    createdAt: datetime
    updatedAt: datetime


class AiJobRequest(BaseModel):
    tenantId: UUID
    jobType: str = "STAGE_REVIEW"
    targetId: str
    payload: Dict[str, Any] = Field(default_factory=dict)
    context: Dict[str, Any] = Field(default_factory=dict)
    agent: Optional[str] = None


class AiJobResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    jobId: UUID
    status: str
    jobType: str
    tenantId: UUID
    targetId: str
    createdAt: datetime
    updatedAt: datetime
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class KnowledgeEntryRequest(BaseModel):
    title: str
    body: str
    tags: List[str] = Field(default_factory=list)
    sourceUri: Optional[str] = None
    embedding: Optional[List[float]] = None


class KnowledgeEntryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    entryId: UUID
    title: str
    body: str
    tags: List[str]
    sourceUri: Optional[str] = None
    embedding: Optional[List[float]] = None
    createdAt: datetime
    updatedAt: datetime


class KnowledgeSearchRequest(BaseModel):
    tenantId: UUID
    query: str
    limit: int = 5
    tags: List[str] = Field(default_factory=list)


class KnowledgeSearchHit(BaseModel):
    entryId: UUID
    title: str
    snippet: str
    score: float
    sourceUri: Optional[str] = None
    tags: List[str]


def to_knowledge_response(model: KnowledgeEntry) -> KnowledgeEntryResponse:
    return KnowledgeEntryResponse(
        entryId=model.entry_id,
        title=model.title,
        body=model.body,
        tags=model.tags,
        sourceUri=model.source_uri,
        embedding=model.embedding,
        createdAt=model.created_at,
        updatedAt=model.updated_at,
    )


def to_mcp_response(model: AiMcp) -> AiMcpResponse:
    return AiMcpResponse(
        profileId=model.profile_id,
        name=model.name,
        type=model.type,
        endpoint=model.endpoint,
        entrypoint=model.entrypoint,
        params=model.params,
        enabled=model.enabled,
        createdAt=model.created_at,
        updatedAt=model.updated_at,
    )


def to_agent_response(model: AiAgent) -> AiAgentResponse:
    return AiAgentResponse(
        agentId=model.agent_id,
        name=model.name,
        kind=model.kind,
        entrypoint=model.entrypoint,
        params=model.params,
        stageTypes=model.stage_types,
        mcpProfileId=model.mcp_profile_id,
        enabled=model.enabled,
        createdAt=model.created_at,
        updatedAt=model.updated_at,
    )


def to_job_response(model: AiJob) -> AiJobResponse:
    return AiJobResponse(
        jobId=model.job_id,
        status=model.status,
        jobType=model.job_type,
        tenantId=model.tenant_id,
        targetId=model.target_id,
        createdAt=model.created_at,
        updatedAt=model.updated_at,
        result=model.result,
        error=model.error,
    )
