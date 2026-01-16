from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

SARIF_ROOT = Path(os.getenv("SARIF_RULE_ROOT", os.getcwd())).resolve()
ALLOWED_EXTENSIONS = tuple(
    ext.strip().lower() for ext in os.getenv("SARIF_RULE_ALLOWED_EXTS", ".sarif,.json").split(",") if ext.strip()
)
MAX_RULES = int(os.getenv("SARIF_RULE_MAX_RULES", "1000"))
MAX_FILE_SIZE_MB = int(os.getenv("SARIF_RULE_MAX_FILE_MB", "25"))

app = FastAPI(title="SARIF Rule Lookup MCP", version="0.1.0")


class SarifRuleRequest(BaseModel):
    path: str = Field(..., description="SARIF file path relative to SARIF_RULE_ROOT")
    rule_id: str = Field(..., alias="ruleId", description="Rule ID to retrieve (SARIF tool.driver.rules[].id)")

    model_config = {"populate_by_name": True}


class SarifMessage(BaseModel):
    text: Optional[str] = None
    markdown: Optional[str] = None


class SarifRuleResponse(BaseModel):
    ruleId: str
    name: Optional[str] = None
    shortDescription: Optional[SarifMessage] = None
    fullDescription: Optional[SarifMessage] = None
    defaultConfiguration: Optional[Dict[str, str]] = None
    helpUri: Optional[str] = None
    properties: Optional[Dict[str, str]] = None


def _ensure_file(path: Path) -> Path:
    try:
        candidate = path.resolve()
    except OSError as exc:
        raise HTTPException(status_code=400, detail=f"Unable to resolve path: {exc}") from exc
    if not str(candidate).startswith(str(SARIF_ROOT)):
        raise HTTPException(status_code=400, detail="SARIF path escapes configured SARIF_RULE_ROOT")
    if not candidate.is_file():
        raise HTTPException(status_code=404, detail="SARIF file not found")
    if ALLOWED_EXTENSIONS:
        if candidate.suffix.lower() not in ALLOWED_EXTENSIONS:
            raise HTTPException(status_code=400, detail="File extension not allowed for SARIF lookup")
    stat = candidate.stat()
    if stat.st_size > MAX_FILE_SIZE_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail="SARIF file exceeds allowed size")
    return candidate


def _load_sarif(file_path: Path) -> Dict:
    with file_path.open("r", encoding="utf-8") as handle:
        try:
            return json.load(handle)
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=400, detail=f"Invalid SARIF JSON: {exc}") from exc


@lru_cache(maxsize=16)
def _rules_cache(file_path: Path) -> Dict[str, Dict]:
    sarif = _load_sarif(file_path)
    rules_index: Dict[str, Dict] = {}
    for run in sarif.get("runs", []):
        tool = run.get("tool", {})
        driver = tool.get("driver", {})
        rules = driver.get("rules") or []
        if not isinstance(rules, list):
            continue
        for rule in rules[:MAX_RULES]:
            rule_id = rule.get("id")
            if not rule_id or rule_id in rules_index:
                continue
            rules_index[rule_id] = rule
    return rules_index


@app.get("/healthz")
def health() -> dict:
    return {"status": "ok", "root": str(SARIF_ROOT)}


@app.post("/tools/sarif.rule:invoke", response_model=SarifRuleResponse)
def lookup_rule(request: SarifRuleRequest) -> SarifRuleResponse:
    relative = request.path.strip()
    if not relative:
        raise HTTPException(status_code=400, detail="path must not be empty")
    sarif_path = _ensure_file(SARIF_ROOT / relative)
    rules = _rules_cache(sarif_path)
    rule = rules.get(request.rule_id)
    if rule is None:
        raise HTTPException(status_code=404, detail="Rule not found in SARIF report")

    def _coerce_msg(value: Optional[Dict]) -> Optional[SarifMessage]:
        if not value:
            return None
        return SarifMessage(text=value.get("text"), markdown=value.get("markdown"))

    return SarifRuleResponse(
        ruleId=request.rule_id,
        name=rule.get("name"),
        shortDescription=_coerce_msg(rule.get("shortDescription")),
        fullDescription=_coerce_msg(rule.get("fullDescription")),
        defaultConfiguration=rule.get("defaultConfiguration"),
        helpUri=rule.get("helpUri"),
        properties=rule.get("properties"),
    )
