from __future__ import annotations

import json
import os
import shutil
import tarfile
import time
import threading
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional
from uuid import UUID

from fastapi import (
    BackgroundTasks,
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    UploadFile,
    status,
)
from sqlmodel import Session, select

from .builtin import register_builtin_routes
from .database import get_session, init_db
from .knowledge import search_knowledge_entries
from .models import AiAgent, AiJob, AiMcp, KnowledgeEntry
from .schemas import (
    AiAgentRequest,
    AiAgentResponse,
    AiJobRequest,
    AiJobResponse,
    AiMcpRequest,
    AiMcpResponse,
    KnowledgeEntryRequest,
    KnowledgeEntryResponse,
    KnowledgeSearchHit,
    KnowledgeSearchRequest,
    to_agent_response,
    to_job_response,
    to_knowledge_response,
    to_mcp_response,
)

AI_SERVICE_TOKEN = os.getenv("SECRUX_AI_SERVICE_TOKEN", "local-dev-token")
MCP_STORAGE_PATH = Path(os.getenv("AI_MCP_STORAGE", "/app/storage/mcps")).resolve()
MCP_STORAGE_PATH.mkdir(parents=True, exist_ok=True)
AGENT_STORAGE_PATH = Path(os.getenv("AI_AGENT_STORAGE", "/app/storage/agents")).resolve()
AGENT_STORAGE_PATH.mkdir(parents=True, exist_ok=True)
AI_BUILTIN_BASE_URL = os.getenv("AI_BUILTIN_BASE_URL", "http://127.0.0.1:5156/builtin")


@dataclass(frozen=True)
class BuiltinMcpDefinition:
    key: str
    type: str
    name: str
    description: str
    endpoint: str


@dataclass(frozen=True)
class BuiltinAgentDefinition:
    key: str
    name: str
    description: str
    stage_types: List[str]
    kind: str = "mcp-review"
    tool: Optional[str] = None
    mcp_type: Optional[str] = None
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = None


BUILTIN_MCP_DEFINITIONS: List[BuiltinMcpDefinition] = [
    BuiltinMcpDefinition(
        key="file-reader-full",
        type="builtin:file-reader-full",
        name="内置文件读取（全量）",
        description="Read entire files with line numbers via /builtin/file-reader.",
        endpoint=f"{AI_BUILTIN_BASE_URL}/file-reader",
    ),
    BuiltinMcpDefinition(
        key="file-reader-range",
        type="builtin:file-reader-range",
        name="内置文件读取（行范围）",
        description="Read specific line ranges via /builtin/file-reader.",
        endpoint=f"{AI_BUILTIN_BASE_URL}/file-reader",
    ),
    BuiltinMcpDefinition(
        key="sarif-rule",
        type="builtin:sarif-rule",
        name="SARIF 规则解析",
        description="Lookup rule metadata from SARIF 2.1.0 results.",
        endpoint=f"{AI_BUILTIN_BASE_URL}/sarif-rules",
    ),
    BuiltinMcpDefinition(
        key="knowledge-search",
        type="builtin:knowledge-search",
        name="知识库检索",
        description="Query tenant knowledge base entries.",
        endpoint=f"{AI_BUILTIN_BASE_URL}/knowledge",
    ),
]

BUILTIN_AGENT_DEFINITIONS: List[BuiltinAgentDefinition] = [
    BuiltinAgentDefinition(
        key="agent-file-reader-full",
        name="内置Agent：文件全量读取",
        description="Expose file.read.full tool for generic stages.",
        stage_types=["SOURCE_PREPARE", "RESULT_PROCESS", "REVIEW"],
        tool="file.read.full",
        mcp_type="builtin:file-reader-full",
    ),
    BuiltinAgentDefinition(
        key="agent-file-reader-range",
        name="内置Agent：文件行范围读取",
        description="Expose file.read.range tool for targeted snippets.",
        stage_types=["RESULT_PROCESS", "REVIEW"],
        tool="file.read.range",
        mcp_type="builtin:file-reader-range",
    ),
    BuiltinAgentDefinition(
        key="agent-sarif-rule",
        name="内置Agent：SARIF 规则解析",
        description="Expose sarif.rule tool for explaining findings.",
        stage_types=["RESULT_PROCESS", "REVIEW"],
        tool="sarif.rule",
        mcp_type="builtin:sarif-rule",
    ),
    BuiltinAgentDefinition(
        key="agent-knowledge-search",
        name="内置Agent：知识库检索",
        description="Expose knowledge.search tool for RAG lookups.",
        stage_types=["RESULT_PROCESS", "REVIEW"],
        tool="knowledge.search",
        mcp_type="builtin:knowledge-search",
    ),
    BuiltinAgentDefinition(
        key="agent-vuln-review-simple",
        name="内置Agent：漏洞复核（简略）",
        description="Quick AI review using code snippet and location.",
        stage_types=["RESULT_PROCESS", "REVIEW"],
        kind="custom",
        entrypoint="secrux_ai.agents.vuln_review:VulnReviewAgent",
        params={"mode": "simple"},
    ),
    BuiltinAgentDefinition(
        key="agent-vuln-review-precise",
        name="内置Agent：漏洞复核（精确）",
        description="Precise AI review using dataflow and AST extraction.",
        stage_types=["RESULT_PROCESS", "REVIEW"],
        kind="custom",
        entrypoint="secrux_ai.agents.vuln_review:VulnReviewAgent",
        params={"mode": "precise", "ast_depth": 1},
    ),
    BuiltinAgentDefinition(
        key="agent-sca-issue-review-simple",
        name="内置Agent：SCA 缺陷复核（简略）",
        description="Quick AI review for one SCA issue using dependency usage evidence.",
        stage_types=["RESULT_PROCESS", "REVIEW"],
        kind="custom",
        entrypoint="secrux_ai.agents.sca_issue_review:ScaIssueReviewAgent",
        params={"mode": "simple"},
    ),
]

app = FastAPI(title="Secrux AI Service", version="0.4.0")

_JOB_SECRETS: dict[UUID, dict[str, str]] = {}
_JOB_SECRETS_LOCK = threading.Lock()


@app.on_event("startup")
def startup() -> None:
    init_db()


def require_token(x_platform_token: str = Header(...)) -> None:
    if x_platform_token != AI_SERVICE_TOKEN:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid platform token")


register_builtin_routes(app, dependencies=[Depends(require_token)])


def _get_session() -> Session:
    with get_session() as session:
        yield session


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/mcps", dependencies=[Depends(require_token)])
def list_mcps(
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> Dict[str, List[AiMcpResponse]]:
    _ensure_builtin_mcps(tenant_id, session)
    records = session.exec(select(AiMcp).where(AiMcp.tenant_id == tenant_id)).all()
    return {"data": [to_mcp_response(record) for record in records]}


@app.post("/api/v1/mcps", dependencies=[Depends(require_token)])
def create_mcp(
    request: AiMcpRequest,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> AiMcpResponse:
    entity = AiMcp(
        tenant_id=tenant_id,
        name=request.name,
        type=request.type,
        endpoint=request.endpoint,
        entrypoint=request.entrypoint,
        params=request.params,
        enabled=request.enabled,
    )
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_mcp_response(entity)


@app.put("/api/v1/mcps/{profile_id}", dependencies=[Depends(require_token)])
def update_mcp(
    profile_id: UUID,
    request: AiMcpRequest,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> AiMcpResponse:
    entity = session.get(AiMcp, profile_id)
    if entity is None or entity.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="MCP not found")
    entity.name = request.name
    entity.type = request.type
    entity.endpoint = request.endpoint
    entity.entrypoint = request.entrypoint
    entity.params = request.params
    entity.enabled = request.enabled
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_mcp_response(entity)


@app.delete("/api/v1/mcps/{profile_id}", dependencies=[Depends(require_token)])
def delete_mcp(
    profile_id: UUID,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> Dict[str, str]:
    entity = session.get(AiMcp, profile_id)
    if entity is None or entity.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="MCP not found")
    session.delete(entity)
    session.commit()
    return {"status": "deleted"}


def _ensure_within_directory(base_dir: Path, target: Path) -> None:
    base = base_dir.resolve()
    try:
        candidate = target.resolve()
    except OSError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unable to resolve archive entry path: {exc}",
        ) from exc
    if not candidate.is_relative_to(base):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Archive entry would escape target directory",
        )


def _extract_archive(archive_path: Path, target_dir: Path) -> None:
    if zipfile.is_zipfile(archive_path):
        with zipfile.ZipFile(archive_path) as zf:
            for member in zf.namelist():
                extracted = target_dir / member
                _ensure_within_directory(target_dir, extracted)
            zf.extractall(target_dir)
    elif tarfile.is_tarfile(archive_path):
        with tarfile.open(archive_path) as tf:
            for member in tf.getmembers():
                extracted = target_dir / (member.name or "")
                _ensure_within_directory(target_dir, extracted)
            tf.extractall(target_dir)
    else:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported archive format")
    archive_path.unlink(missing_ok=True)


def _ensure_builtin_mcps(tenant_id: UUID, session: Session) -> None:
    created = False
    for definition in BUILTIN_MCP_DEFINITIONS:
        exists = session.exec(
            select(AiMcp).where(AiMcp.tenant_id == tenant_id, AiMcp.type == definition.type)
        ).first()
        if exists:
            continue
        entity = AiMcp(
            tenant_id=tenant_id,
            name=definition.name,
            type=definition.type,
            endpoint=definition.endpoint,
            entrypoint=None,
            params={"builtinKey": definition.key, "description": definition.description},
            enabled=True,
        )
        session.add(entity)
        created = True
    if created:
        session.commit()


def _ensure_builtin_agents(tenant_id: UUID, session: Session) -> None:
    _ensure_builtin_mcps(tenant_id, session)
    created = False
    for definition in BUILTIN_AGENT_DEFINITIONS:
        exists = session.exec(
            select(AiAgent).where(AiAgent.tenant_id == tenant_id, AiAgent.name == definition.name)
        ).first()
        if exists:
            continue
        mcp = None
        if definition.mcp_type:
            mcp = session.exec(
                select(AiMcp).where(AiMcp.tenant_id == tenant_id, AiMcp.type == definition.mcp_type)
            ).first()
            if mcp is None:
                continue
        entity = AiAgent(
            tenant_id=tenant_id,
            name=definition.name,
            kind=definition.kind,
            entrypoint=definition.entrypoint,
            params=definition.params or {"tool": definition.tool, "builtinKey": definition.key},
            stage_types=definition.stage_types,
            mcp_profile_id=mcp.profile_id if mcp else None,
            enabled=True,
        )
        session.add(entity)
        created = True
    if created:
        session.commit()


@app.post("/api/v1/mcps/upload", dependencies=[Depends(require_token)])
def upload_mcp(
    tenant_id: UUID = Query(..., alias="tenantId"),
    file: UploadFile = File(...),
    name: str = Form(...),
    entrypoint: str | None = Form(None),
    enabled: bool = Form(True),
    params: str = Form("{}"),
    session: Session = Depends(_get_session),
) -> AiMcpResponse:
    try:
        params_dict = json.loads(params) if params else {}
        if not isinstance(params_dict, dict):
            raise ValueError("params must be a JSON object")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))

    profile_id = uuid.uuid4()
    tenant_dir = MCP_STORAGE_PATH / str(tenant_id)
    tenant_dir.mkdir(parents=True, exist_ok=True)
    target_dir = tenant_dir / str(profile_id)
    target_dir.mkdir(parents=True, exist_ok=True)
    archive_name = file.filename or "archive.bin"
    archive_path = target_dir / archive_name
    with archive_path.open("wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
    try:
        _extract_archive(archive_path, target_dir)
    except HTTPException:
        shutil.rmtree(target_dir, ignore_errors=True)
        raise
    params_dict.setdefault("rootPath", str(target_dir))
    entity = AiMcp(
        profile_id=profile_id,
        tenant_id=tenant_id,
        name=name,
        type="local",
        endpoint=None,
        entrypoint=entrypoint,
        params=params_dict,
        enabled=enabled,
    )
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_mcp_response(entity)


@app.get("/api/v1/agents", dependencies=[Depends(require_token)])
def list_agents(
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> Dict[str, List[AiAgentResponse]]:
    _ensure_builtin_agents(tenant_id, session)
    records = session.exec(select(AiAgent).where(AiAgent.tenant_id == tenant_id)).all()
    return {"data": [to_agent_response(record) for record in records]}


@app.post("/api/v1/agents", dependencies=[Depends(require_token)])
def create_agent(
    request: AiAgentRequest,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> AiAgentResponse:
    entity = AiAgent(
        tenant_id=tenant_id,
        name=request.name,
        kind=request.kind,
        entrypoint=request.entrypoint,
        params=request.params,
        stage_types=request.stageTypes,
        mcp_profile_id=request.mcpProfileId,
        enabled=request.enabled,
    )
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_agent_response(entity)


@app.get("/api/v1/knowledge", dependencies=[Depends(require_token)])
def list_knowledge_entries(
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> Dict[str, List[KnowledgeEntryResponse]]:
    records = session.exec(select(KnowledgeEntry).where(KnowledgeEntry.tenant_id == tenant_id)).all()
    return {"data": [to_knowledge_response(record) for record in records]}


@app.post("/api/v1/knowledge", dependencies=[Depends(require_token)])
def create_knowledge_entry(
    request: KnowledgeEntryRequest,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> KnowledgeEntryResponse:
    entity = KnowledgeEntry(
        tenant_id=tenant_id,
        title=request.title,
        body=request.body,
        tags=request.tags,
        source_uri=request.sourceUri,
        embedding=request.embedding,
    )
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_knowledge_response(entity)


@app.put("/api/v1/knowledge/{entry_id}", dependencies=[Depends(require_token)])
def update_knowledge_entry(
    entry_id: UUID,
    request: KnowledgeEntryRequest,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> KnowledgeEntryResponse:
    entity = session.get(KnowledgeEntry, entry_id)
    if entity is None or entity.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Knowledge entry not found")
    entity.title = request.title
    entity.body = request.body
    entity.tags = request.tags
    entity.source_uri = request.sourceUri
    entity.embedding = request.embedding
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_knowledge_response(entity)


@app.delete("/api/v1/knowledge/{entry_id}", dependencies=[Depends(require_token)])
def delete_knowledge_entry(
    entry_id: UUID,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> Dict[str, str]:
    entity = session.get(KnowledgeEntry, entry_id)
    if entity is None or entity.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Knowledge entry not found")
    session.delete(entity)
    session.commit()
    return {"status": "deleted"}


@app.post("/api/v1/knowledge/search", dependencies=[Depends(require_token)])
def search_knowledge(
    request: KnowledgeSearchRequest,
    session: Session = Depends(_get_session),
) -> Dict[str, List[KnowledgeSearchHit]]:
    hits = search_knowledge_entries(
        tenant_id=request.tenantId,
        query=request.query,
        limit=max(1, min(request.limit, 20)),
        tags=request.tags,
        session=session,
    )
    return {"data": hits}


@app.put("/api/v1/agents/{agent_id}", dependencies=[Depends(require_token)])
def update_agent(
    agent_id: UUID,
    request: AiAgentRequest,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> AiAgentResponse:
    entity = session.get(AiAgent, agent_id)
    if entity is None or entity.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Agent not found")
    entity.name = request.name
    entity.kind = request.kind
    entity.entrypoint = request.entrypoint
    entity.params = request.params
    entity.stage_types = request.stageTypes
    entity.mcp_profile_id = request.mcpProfileId
    entity.enabled = request.enabled
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_agent_response(entity)


@app.delete("/api/v1/agents/{agent_id}", dependencies=[Depends(require_token)])
def delete_agent(
    agent_id: UUID,
    tenant_id: UUID = Query(..., alias="tenantId"),
    session: Session = Depends(_get_session),
) -> Dict[str, str]:
    entity = session.get(AiAgent, agent_id)
    if entity is None or entity.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Agent not found")
    session.delete(entity)
    session.commit()
    return {"status": "deleted"}


@app.post("/api/v1/agents/upload", dependencies=[Depends(require_token)])
def upload_agent(
    tenant_id: UUID = Query(..., alias="tenantId"),
    file: UploadFile = File(...),
    name: str = Form(...),
    kind: str = Form(...),
    entrypoint: str | None = Form(None),
    stageTypes: str = Form("[]"),
    mcpProfileId: str | None = Form(None),
    enabled: bool = Form(True),
    params: str = Form("{}"),
    session: Session = Depends(_get_session),
) -> AiAgentResponse:
    try:
        stage_types = json.loads(stageTypes) if stageTypes else []
        if not isinstance(stage_types, list) or any(not isinstance(value, str) for value in stage_types):
            raise ValueError("stageTypes must be a JSON array of strings")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))

    try:
        params_dict = json.loads(params) if params else {}
        if not isinstance(params_dict, dict):
            raise ValueError("params must be a JSON object")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))

    tenant_dir = AGENT_STORAGE_PATH / str(tenant_id)
    tenant_dir.mkdir(parents=True, exist_ok=True)
    agent_dir = tenant_dir / str(uuid.uuid4())
    agent_dir.mkdir(parents=True, exist_ok=True)
    archive_name = file.filename or "agent.zip"
    archive_path = agent_dir / archive_name

    with archive_path.open("wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    try:
        _extract_archive(archive_path, agent_dir)
    except HTTPException:
        shutil.rmtree(agent_dir, ignore_errors=True)
        raise

    params_dict.setdefault("pluginPath", str(agent_dir))
    mcp_profile_uuid = None
    if mcpProfileId:
        try:
            mcp_profile_uuid = UUID(mcpProfileId)
        except ValueError:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid MCP profile id")

    entity = AiAgent(
        tenant_id=tenant_id,
        name=name,
        kind=kind,
        entrypoint=entrypoint,
        params=params_dict,
        stage_types=stage_types,
        mcp_profile_id=mcp_profile_uuid,
        enabled=enabled,
    )
    session.add(entity)
    session.commit()
    session.refresh(entity)
    return to_agent_response(entity)


@app.post("/api/v1/jobs/reviews", dependencies=[Depends(require_token)])
def submit_job(
    request: AiJobRequest,
    background_tasks: BackgroundTasks,
    session: Session = Depends(_get_session),
) -> AiJobResponse:
    context = dict(request.context or {})
    if request.agent:
        context["agent"] = request.agent

    payload = dict(request.payload or {})
    secret: dict[str, str] | None = None
    ai_client = payload.get("aiClient")
    if isinstance(ai_client, dict):
        api_key = ai_client.get("apiKey")
        if isinstance(api_key, str) and api_key.strip():
            secret = {"apiKey": api_key.strip()}
            redacted = dict(ai_client)
            redacted.pop("apiKey", None)
            payload["aiClient"] = redacted
    job = AiJob(
        tenant_id=request.tenantId,
        job_type=request.jobType,
        target_id=request.targetId,
        payload=payload,
        context=context,
        status="QUEUED",
    )
    session.add(job)
    session.commit()
    session.refresh(job)
    if secret:
        with _JOB_SECRETS_LOCK:
            _JOB_SECRETS[job.job_id] = secret
    background_tasks.add_task(_execute_review_job, job.job_id)
    return to_job_response(job)


@app.get("/api/v1/jobs/{job_id}", dependencies=[Depends(require_token)])
def get_job(job_id: UUID, session: Session = Depends(_get_session)) -> AiJobResponse:
    job = session.get(AiJob, job_id)
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="job not found")
    return to_job_response(job)


def _execute_review_job(job_id: UUID) -> None:
    from datetime import datetime, timezone

    from sqlmodel import select

    from secrux_ai.config import AgentConfig, CallbackConfig, PlatformConfig
    from secrux_ai.models import StageEvent, StageSignals, StageStatus, StageType
    from secrux_ai.orchestrator import AgentOrchestrator

    def now_utc() -> datetime:
        return datetime.now(timezone.utc)

    def resolve_stage_status(value: object) -> StageStatus:
        if isinstance(value, str):
            try:
                return StageStatus(value)
            except Exception:
                return StageStatus.SUCCEEDED
        return StageStatus.SUCCEEDED

    def resolve_mode(context: dict) -> str:
        mode = context.get("mode")
        if isinstance(mode, str) and mode.strip():
            return mode.strip().lower()
        return "simple"

    def build_event(job: AiJob, secret: dict[str, str] | None) -> StageEvent:
        created_at = job.created_at
        updated_at = job.updated_at or created_at
        ctx = job.context or {}
        status = resolve_stage_status(ctx.get("status"))

        if job.job_type == "FINDING_REVIEW":
            finding_payload = {}
            payload_finding = (job.payload or {}).get("finding")
            if isinstance(payload_finding, dict):
                finding_payload = payload_finding
            ai_client = (job.payload or {}).get("aiClient")
            if isinstance(ai_client, dict) and secret and secret.get("apiKey"):
                ai_client = dict(ai_client)
                ai_client["apiKey"] = secret["apiKey"]
            task_id = finding_payload.get("taskId") or job.target_id
            stage_id = finding_payload.get("findingId") or job.target_id
            mode = resolve_mode(job.context or {})
            return StageEvent(
                tenantId=str(job.tenant_id),
                taskId=str(task_id),
                stageId=str(stage_id),
                stageType=StageType.RESULT_REVIEW,
                status=status,
                startedAt=created_at,
                endedAt=updated_at,
                extra={"jobId": str(job.job_id), "mode": mode, "finding": finding_payload, "aiClient": ai_client},
            )

        if job.job_type == "SCA_ISSUE_REVIEW":
            issue_payload = {}
            payload_issue = (job.payload or {}).get("scaIssue")
            if isinstance(payload_issue, dict):
                issue_payload = payload_issue
            ai_client = (job.payload or {}).get("aiClient")
            if isinstance(ai_client, dict) and secret and secret.get("apiKey"):
                ai_client = dict(ai_client)
                ai_client["apiKey"] = secret["apiKey"]
            task_id = issue_payload.get("taskId") or job.target_id
            stage_id = issue_payload.get("issueId") or job.target_id
            mode = resolve_mode(job.context or {})
            return StageEvent(
                tenantId=str(job.tenant_id),
                taskId=str(task_id),
                stageId=str(stage_id),
                stageType=StageType.RESULT_REVIEW,
                status=status,
                startedAt=created_at,
                endedAt=updated_at,
                extra={"jobId": str(job.job_id), "mode": mode, "scaIssue": issue_payload, "aiClient": ai_client},
            )

        payload = dict(job.payload or {})
        ai_client = payload.get("aiClient")
        if isinstance(ai_client, dict) and secret and secret.get("apiKey"):
            ai_client = dict(ai_client)
            ai_client["apiKey"] = secret["apiKey"]
            payload["aiClient"] = ai_client

        context_text = payload.get("context")
        log_excerpt = context_text.splitlines() if isinstance(context_text, str) else []
        needs_ai_review = ctx.get("needsAiReview")
        signals = StageSignals(needsAiReview=bool(needs_ai_review) if needs_ai_review is not None else False)
        return StageEvent(
            tenantId=str(job.tenant_id),
            taskId=job.target_id,
            stageId=job.target_id,
            stageType=StageType.RESULT_REVIEW,
            status=status,
            startedAt=created_at,
            endedAt=updated_at,
            log_excerpt=log_excerpt,
            signals=signals,
            extra={"jobType": job.job_type, "payload": payload},
        )

    def build_platform_config(job: AiJob, session: Session) -> PlatformConfig:
        ctx = job.context or {}
        mode = resolve_mode(ctx)
        requested_agent = ctx.get("agent")
        agent_configs: list[AgentConfig] = []

        if isinstance(requested_agent, str) and requested_agent.strip():
            agent_name = requested_agent.strip()
            from .models import AiAgent

            entity = session.exec(
                select(AiAgent).where(AiAgent.tenant_id == job.tenant_id, AiAgent.name == agent_name)
            ).first()
            if entity is not None and entity.enabled:
                params = dict(entity.params or {})
                if "mode" not in params and mode:
                    params["mode"] = mode
                agent_configs.append(
                    AgentConfig(
                        name=entity.name,
                        kind=entity.kind,
                        entrypoint=entity.entrypoint,
                        enabled=True,
                        params=params,
                        stageTypes=entity.stage_types,
                        mcpProfile=str(entity.mcp_profile_id) if entity.mcp_profile_id else None,
                    )
                )
            else:
                # Treat the request value as a builtin kind.
                agent_configs.append(AgentConfig(name=agent_name, kind=agent_name, enabled=True, params={"mode": mode}))
        else:
            if job.job_type == "FINDING_REVIEW":
                agent_configs.append(
                    AgentConfig(name="vuln-review", kind="vuln-review", enabled=True, params={"mode": mode})
                )
            elif job.job_type == "SCA_ISSUE_REVIEW":
                agent_configs.append(
                    AgentConfig(name="sca-issue-review", kind="sca-issue-review", enabled=True, params={"mode": mode})
                )
            else:
                agent_configs.append(AgentConfig(name="signal", kind="signal", enabled=True))
                agent_configs.append(AgentConfig(name="log", kind="log", enabled=True))

        return PlatformConfig(agents=agent_configs, callbacks=CallbackConfig(mode="stdout"))

    def summarize_result(findings: list[dict]) -> str:
        if not findings:
            return "AI review completed (no findings)"
        top = findings[0]
        if isinstance(top, dict):
            return top.get("summary") or "AI review completed"
        return "AI review completed"

    def resolve_severity(findings: list[dict]) -> str:
        order = {"CRITICAL": 5, "HIGH": 4, "MEDIUM": 3, "LOW": 2, "INFO": 1}
        severity = "INFO"
        best = 0
        for finding in findings:
            sev = finding.get("severity") if isinstance(finding, dict) else None
            if isinstance(sev, str) and order.get(sev, 0) > best:
                best = order[sev]
                severity = sev
        return severity

    def extract_top(values: object) -> dict | None:
        if not isinstance(values, list) or not values:
            return None
        top = values[0]
        return top if isinstance(top, dict) else None

    def extract_suggested_status(top: dict | None) -> str | None:
        if not top:
            return None
        status = top.get("status")
        return status if isinstance(status, str) else None

    def extract_llm(top: dict | None) -> dict | None:
        if not top:
            return None
        details = top.get("details")
        if not isinstance(details, dict):
            return None
        llm = details.get("llm")
        return llm if isinstance(llm, dict) else None

    def extract_opinion_i18n(top: dict | None) -> dict | None:
        if not top:
            return None
        details = top.get("details")
        if not isinstance(details, dict):
            return None
        opinion = details.get("opinionI18n")
        return opinion if isinstance(opinion, dict) else None

    def extract_confidence(llm: dict | None) -> float | None:
        if not llm:
            return None
        value = llm.get("confidence")
        if isinstance(value, (int, float)):
            return float(value)
        try:
            return float(str(value))
        except Exception:
            return None

    with get_session() as session:
        job = session.get(AiJob, job_id)
        if job is None:
            return
        job.status = "RUNNING"
        job.updated_at = now_utc()
        session.add(job)
        session.commit()

    secret: dict[str, str] | None = None
    with _JOB_SECRETS_LOCK:
        secret = _JOB_SECRETS.get(job_id)

    try:
        with get_session() as session:
            job = session.get(AiJob, job_id)
            if job is None:
                return

            from fastapi.encoders import jsonable_encoder

            event = build_event(job, secret)
            platform_config = build_platform_config(job, session)
            with AgentOrchestrator(platform_config) as orchestrator:
                recommendation = orchestrator.process(event)
            recommendation_payload = recommendation.model_dump(mode="json", by_alias=True)
            findings = recommendation_payload.get("findings", [])
            top_finding = extract_top(findings)
            llm = extract_llm(top_finding)
            suggested_status = extract_suggested_status(top_finding)
            severity = resolve_severity(findings) if isinstance(findings, list) else "INFO"
            verdict = (llm.get("verdict") if llm else None) or "UNCERTAIN"
            confidence = extract_confidence(llm) or 0.6
            opinion_i18n = (llm.get("opinionI18n") if llm else None) or extract_opinion_i18n(top_finding)
            fix_hint = llm.get("fixHint") if llm else None
            if not fix_hint and isinstance(opinion_i18n, dict):
                en_opinion = opinion_i18n.get("en")
                if isinstance(en_opinion, dict):
                    fix_hint = en_opinion.get("fixHint")
            result = {
                "reviewType": "AI",
                "verdict": verdict,
                "severity": severity,
                "suggestedStatus": suggested_status,
                "confidence": confidence,
                "summary": summarize_result(findings) if isinstance(findings, list) else "AI review completed",
                "fixHint": fix_hint,
                "opinionI18n": opinion_i18n,
                "recommendation": recommendation_payload,
            }

            job.status = "COMPLETED"
            job.updated_at = now_utc()
            job.result = jsonable_encoder(result)
            session.add(job)
            session.commit()
    except Exception as exc:
        with get_session() as session:
            job = session.get(AiJob, job_id)
            if job is None:
                return
            job.status = "FAILED"
            job.updated_at = now_utc()
            job.error = str(exc)
            session.add(job)
            session.commit()
    finally:
        with _JOB_SECRETS_LOCK:
            _JOB_SECRETS.pop(job_id, None)
