from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from sqlmodel import Field, SQLModel
from sqlalchemy import Column
try:
    from sqlalchemy.dialects.postgresql import JSONB as JSONType
except ImportError:  # pragma: no cover - fallback for non-Postgres dev setups
    from sqlalchemy import JSON as JSONType


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class AiMcp(SQLModel, table=True):
    profile_id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True, alias="profileId")
    tenant_id: uuid.UUID = Field(index=True, alias="tenantId")
    name: str
    type: str
    endpoint: Optional[str] = None
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict, sa_column=Column(JSONType))
    enabled: bool = True
    created_at: datetime = Field(default_factory=utcnow, alias="createdAt")
    updated_at: datetime = Field(default_factory=utcnow, alias="updatedAt")


class AiAgent(SQLModel, table=True):
    agent_id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True, alias="agentId")
    tenant_id: uuid.UUID = Field(index=True, alias="tenantId")
    name: str
    kind: str
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict, sa_column=Column(JSONType))
    stage_types: List[str] = Field(default_factory=list, alias="stageTypes", sa_column=Column(JSONType))
    mcp_profile_id: Optional[uuid.UUID] = Field(default=None, alias="mcpProfileId")
    enabled: bool = True
    created_at: datetime = Field(default_factory=utcnow, alias="createdAt")
    updated_at: datetime = Field(default_factory=utcnow, alias="updatedAt")


class AiJob(SQLModel, table=True):
    job_id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True, alias="jobId")
    tenant_id: uuid.UUID = Field(index=True, alias="tenantId")
    job_type: str = Field(alias="jobType")
    target_id: str = Field(alias="targetId")
    payload: Dict[str, Any] = Field(default_factory=dict, sa_column=Column(JSONType))
    context: Dict[str, Any] = Field(default_factory=dict, sa_column=Column(JSONType))
    status: str
    result: Optional[Dict[str, Any]] = Field(default=None, sa_column=Column(JSONType))
    error: Optional[str] = None
    created_at: datetime = Field(default_factory=utcnow, alias="createdAt")
    updated_at: datetime = Field(default_factory=utcnow, alias="updatedAt")


class KnowledgeEntry(SQLModel, table=True):
    entry_id: uuid.UUID = Field(default_factory=uuid.uuid4, primary_key=True, alias="entryId")
    tenant_id: uuid.UUID = Field(index=True, alias="tenantId")
    title: str
    body: str
    tags: List[str] = Field(default_factory=list, sa_column=Column(JSONType))
    source_uri: Optional[str] = Field(default=None, alias="sourceUri")
    embedding: Optional[List[float]] = Field(default=None, sa_column=Column(JSONType))
    created_at: datetime = Field(default_factory=utcnow, alias="createdAt")
    updated_at: datetime = Field(default_factory=utcnow, alias="updatedAt")
