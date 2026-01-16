from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class StageStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"


class StageType(str, Enum):
    SOURCE_PREPARE = "SOURCE_PREPARE"
    RULES_PREPARE = "RULES_PREPARE"
    SCAN_EXEC = "SCAN_EXEC"
    RESULT_PROCESS = "RESULT_PROCESS"
    RESULT_REVIEW = "RESULT_REVIEW"
    TICKET_CREATE = "TICKET_CREATE"
    FEEDBACK_SYNC = "FEEDBACK_SYNC"


class Severity(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INFO = "INFO"


class FindingStatus(str, Enum):
    OPEN = "OPEN"
    CONFIRMED = "CONFIRMED"
    FALSE_POSITIVE = "FALSE_POSITIVE"
    RESOLVED = "RESOLVED"
    WONT_FIX = "WONT_FIX"


class StageSignals(BaseModel):
    needs_ai_review: bool = Field(False, alias="needsAiReview")
    auto_fix_possible: bool = Field(False, alias="autoFixPossible")
    risk_delta: Optional[float] = Field(None, alias="riskDelta")
    has_sink: Optional[bool] = Field(None, alias="hasSink")
    metadata: Dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)


class StageMetrics(BaseModel):
    duration_ms: Optional[int] = Field(None, alias="durationMs")
    cpu_seconds: Optional[float] = Field(None, alias="cpuSeconds")
    memory_peak_bytes: Optional[int] = Field(None, alias="memoryPeakBytes")
    extra: Dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)


class StageEvent(BaseModel):
    """Payload emitted by Secrux when a workflow stage finishes."""

    model_config = ConfigDict(populate_by_name=True)

    tenant_id: str = Field(..., alias="tenantId")
    task_id: str = Field(..., validation_alias=AliasChoices("taskId", "task_id"))
    stage_id: str = Field(..., validation_alias=AliasChoices("stageId", "stage_id"))
    stage_type: StageType = Field(..., alias="stageType")
    task_type: Optional[str] = Field(None, alias="taskType")
    project_id: Optional[str] = Field(None, alias="projectId")
    correlation_id: Optional[str] = Field(None, alias="correlationId")
    status: StageStatus
    started_at: datetime = Field(..., alias="startedAt")
    ended_at: datetime = Field(..., alias="endedAt")
    artifacts: List[str] = Field(default_factory=list)
    log_excerpt: List[str] = Field(default_factory=list)
    signals: StageSignals = Field(default_factory=StageSignals)
    metrics: StageMetrics = Field(default_factory=StageMetrics)
    extra: Dict[str, Any] = Field(default_factory=dict)


class AgentFinding(BaseModel):
    agent: str
    severity: Severity
    status: FindingStatus = FindingStatus.OPEN
    summary: str
    details: Dict[str, Any] = Field(default_factory=dict)


class AgentRecommendation(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task_id: str = Field(..., alias="taskId")
    stage_id: str = Field(..., alias="stageId")
    stage_type: StageType = Field(..., alias="stageType")
    generated_at: datetime = Field(default_factory=datetime.utcnow, alias="generatedAt")
    findings: List[AgentFinding] = Field(default_factory=list)
    elapsed_ms: int = Field(..., alias="elapsedMs")
    metadata: Dict[str, Any] = Field(default_factory=dict)


class AgentContext(BaseModel):
    event: StageEvent
    shared_cache: Dict[str, Any] = Field(default_factory=dict)
    # Keep this as `Any` to avoid runtime forward-ref issues with optional MCP integrations.
    mcp_client: Optional[Any] = None


class CallbackResponse(BaseModel):
    status: str
    message: Optional[str] = None


class ConfigProviderSpec(BaseModel):
    kind: str = "file"
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict)


from typing import TYPE_CHECKING

if TYPE_CHECKING:  # pragma: no cover
    from .mcp.base import BaseMCPClient
