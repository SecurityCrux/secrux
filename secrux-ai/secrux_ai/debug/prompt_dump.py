from __future__ import annotations

import json
import os
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional


_LOCK = threading.Lock()


def _now_utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def _dump_mode() -> str:
    raw = (os.getenv("SECRUX_AI_PROMPT_DUMP") or "").strip().lower()
    if raw in ("", "0", "false", "off", "disabled", "none"):
        return "off"
    if raw in ("1", "true", "on", "enabled", "file"):
        return "file"
    if raw in ("stdout", "console"):
        return "stdout"
    return "off"


def _dump_dir() -> Path:
    value = (os.getenv("SECRUX_AI_PROMPT_DUMP_DIR") or "/app/storage/prompt-dumps").strip()
    return Path(value).expanduser().resolve()

def _dump_file_strategy() -> str:
    raw = (os.getenv("SECRUX_AI_PROMPT_DUMP_FILE_STRATEGY") or "").strip().lower()
    if raw in ("", "target", "finding", "vuln", "per_target", "per-target"):
        return "target"
    if raw in ("daily", "day"):
        return "daily"
    if raw in ("job", "job_id", "per_job", "per-job"):
        return "job"
    if raw in ("record", "event", "per_record", "per-record"):
        return "record"
    return "target"


def _include_response() -> bool:
    raw = (os.getenv("SECRUX_AI_PROMPT_DUMP_INCLUDE_RESPONSE") or "").strip().lower()
    return raw in ("1", "true", "on", "yes")

def _include_finding() -> bool:
    raw = (os.getenv("SECRUX_AI_PROMPT_DUMP_INCLUDE_FINDING") or "").strip().lower()
    return raw in ("1", "true", "on", "yes")


def _include_enrichment() -> bool:
    raw = (os.getenv("SECRUX_AI_PROMPT_DUMP_INCLUDE_ENRICHMENT") or "").strip().lower()
    return raw in ("1", "true", "on", "yes")


def _filter_value(name: str) -> Optional[str]:
    value = (os.getenv(name) or "").strip()
    return value if value else None


def _should_dump(*, job_id: Optional[str], target_id: Optional[str], agent: Optional[str], mode: Optional[str], purpose: Optional[str]) -> bool:
    if _dump_mode() == "off":
        return False
    f_job = _filter_value("SECRUX_AI_PROMPT_DUMP_FILTER_JOB_ID")
    if f_job and (job_id or "") != f_job:
        return False
    f_target = _filter_value("SECRUX_AI_PROMPT_DUMP_FILTER_TARGET_ID")
    if f_target and (target_id or "") != f_target:
        return False
    f_agent = _filter_value("SECRUX_AI_PROMPT_DUMP_FILTER_AGENT")
    if f_agent and (agent or "") != f_agent:
        return False
    f_mode = _filter_value("SECRUX_AI_PROMPT_DUMP_FILTER_MODE")
    if f_mode and (mode or "") != f_mode:
        return False
    f_purpose = _filter_value("SECRUX_AI_PROMPT_DUMP_FILTER_PURPOSE")
    if f_purpose and (purpose or "") != f_purpose:
        return False
    return True


def _max_chars() -> int:
    raw = (os.getenv("SECRUX_AI_PROMPT_DUMP_MAX_CHARS") or "").strip()
    if not raw:
        return 200_000
    try:
        return max(1_000, int(raw))
    except Exception:
        return 200_000


def _truncate(value: Any, limit: int) -> Any:
    if isinstance(value, str):
        return value if len(value) <= limit else value[:limit] + "…"
    if isinstance(value, dict):
        return {k: _truncate(v, limit) for k, v in value.items()}
    if isinstance(value, list):
        return [_truncate(v, limit) for v in value]
    return value


def dump_llm_request(
    *,
    job_id: Optional[str],
    tenant_id: Optional[str],
    target_id: Optional[str],
    agent: Optional[str],
    mode: Optional[str],
    purpose: str,
    url: str,
    model: str,
    temperature: float,
    request_body: Dict[str, Any],
) -> None:
    if not _should_dump(job_id=job_id, target_id=target_id, agent=agent, mode=mode, purpose=purpose):
        return
    record = {
        "ts": _now_utc(),
        "type": "llm_request",
        "purpose": purpose,
        "jobId": job_id,
        "tenantId": tenant_id,
        "targetId": target_id,
        "agent": agent,
        "mode": mode,
        "llm": {"url": url, "model": model, "temperature": temperature},
        "requestBody": request_body,
    }
    _write_record(record)


def dump_llm_response(
    *,
    job_id: Optional[str],
    tenant_id: Optional[str],
    target_id: Optional[str],
    agent: Optional[str],
    mode: Optional[str],
    purpose: str,
    url: str,
    model: str,
    response_json: Optional[Dict[str, Any]],
    error: Optional[str] = None,
) -> None:
    if not _should_dump(job_id=job_id, target_id=target_id, agent=agent, mode=mode, purpose=purpose):
        return
    if not _include_response() and error is None:
        return
    record = {
        "ts": _now_utc(),
        "type": "llm_response",
        "purpose": purpose,
        "jobId": job_id,
        "tenantId": tenant_id,
        "targetId": target_id,
        "agent": agent,
        "mode": mode,
        "llm": {"url": url, "model": model},
        "response": response_json,
        "error": error,
    }
    _write_record(record)

def dump_finding_payload(
    *,
    job_id: Optional[str],
    tenant_id: Optional[str],
    target_id: Optional[str],
    agent: Optional[str],
    mode: Optional[str],
    purpose: str = "review",
    finding: Optional[Dict[str, Any]],
) -> None:
    if not _include_finding():
        return
    if not _should_dump(job_id=job_id, target_id=target_id, agent=agent, mode=mode, purpose=purpose):
        return
    record = {
        "ts": _now_utc(),
        "type": "finding_payload",
        "purpose": purpose,
        "jobId": job_id,
        "tenantId": tenant_id,
        "targetId": target_id,
        "agent": agent,
        "mode": mode,
        "finding": finding or {},
    }
    if not _include_enrichment() and isinstance(record.get("finding"), dict):
        (record["finding"] or {}).pop("enrichment", None)
    _write_record(record)


def _write_record(record: Dict[str, Any]) -> None:
    mode = _dump_mode()
    max_chars = _max_chars()
    safe = _truncate(record, max_chars)
    line = json.dumps(safe, ensure_ascii=False)

    if mode == "stdout":
        print(line)
        return
    if mode != "file":
        return

    out_dir = _dump_dir()
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        strategy = _dump_file_strategy()
        if strategy == "daily":
            filename = f"prompt-dump-{datetime.now(timezone.utc).strftime('%Y%m%d')}.jsonl"
            path = out_dir / filename
        elif strategy == "job":
            job_id = str(record.get("jobId") or "unknown")
            filename = f"prompt-dump-job-{job_id}.jsonl"
            path = out_dir / filename
        elif strategy == "record":
            job_id = str(record.get("jobId") or "unknown")
            ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
            rtype = str(record.get("type") or "record")
            filename = f"prompt-dump-{ts}-{rtype}-job-{job_id}.json"
            path = out_dir / filename
        else:
            # Default: one dump file per target (findingId/stageId).
            target_id = str(record.get("targetId") or "unknown")
            purpose = str(record.get("purpose") or "unknown")
            filename = f"prompt-dump-{purpose}-target-{target_id}.jsonl"
            path = out_dir / filename
        with _LOCK:
            with path.open("a", encoding="utf-8") as handle:
                handle.write(line)
                handle.write("\n")
    except Exception:
        # Never crash the agent due to debug dumping.
        return
