from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Optional

import httpx

from ..debug.prompt_dump import dump_llm_request, dump_llm_response
from ..models import AgentContext, AgentFinding, FindingStatus, Severity
from .base import BaseAgent


class TicketCopyAgent(BaseAgent):
    """
    Generate or polish ticket copy (title/description) from a list of findings.
    Expected payload (stage job): context.event.extra.payload = { ticket: {...}, findings: [...], action: generate|polish }
    """

    def run(self, context: AgentContext) -> List[AgentFinding]:
        extra = context.event.extra or {}
        payload = extra.get("payload") if isinstance(extra, dict) else None
        if not isinstance(payload, dict):
            payload = {}

        action = (payload.get("action") or self.params.get("action") or "generate").strip().lower()
        ticket = payload.get("ticket") if isinstance(payload.get("ticket"), dict) else {}
        findings = payload.get("findings") if isinstance(payload.get("findings"), list) else []
        provider = str(ticket.get("provider") or ticket.get("providerKey") or "unknown")

        prompt = self._build_prompt(action=action, provider=provider, ticket=ticket, findings=findings)
        llm_output = self._maybe_call_llm(context, prompt)
        if not llm_output:
            title_i18n, desc_i18n = self._fallback_copy(provider=provider, findings=findings)
            return [
                AgentFinding(
                    agent=self.name,
                    severity=Severity.INFO,
                    status=FindingStatus.OPEN,
                    summary=title_i18n.get("en") or title_i18n.get("zh") or "Ticket copy generated",
                    details={
                        "action": action,
                        "provider": provider,
                        "ticketContent": {
                            "titleI18n": title_i18n,
                            "descriptionI18n": desc_i18n,
                            "labels": [],
                        },
                        "note": "LLM not configured; returned fallback ticket copy.",
                    },
                )
            ]

        title_i18n = llm_output.get("titleI18n") if isinstance(llm_output.get("titleI18n"), dict) else None
        desc_i18n = llm_output.get("descriptionI18n") if isinstance(llm_output.get("descriptionI18n"), dict) else None
        labels = llm_output.get("labels") if isinstance(llm_output.get("labels"), list) else []

        if not title_i18n or not desc_i18n:
            fallback_title, fallback_desc = self._fallback_copy(provider=provider, findings=findings)
            title_i18n = title_i18n or fallback_title
            desc_i18n = desc_i18n or fallback_desc

        summary = (
            (title_i18n.get("en") if isinstance(title_i18n.get("en"), str) else None)
            or (title_i18n.get("zh") if isinstance(title_i18n.get("zh"), str) else None)
            or "Ticket copy generated"
        )

        return [
            AgentFinding(
                agent=self.name,
                severity=Severity.INFO,
                status=FindingStatus.OPEN,
                summary=summary,
                details={
                    "action": action,
                    "provider": provider,
                    "ticketContent": {
                        "titleI18n": title_i18n,
                        "descriptionI18n": desc_i18n,
                        "labels": labels,
                    },
                    "llm": {k: v for k, v in llm_output.items() if k != "raw"},
                },
            )
        ]

    def _build_prompt(self, *, action: str, provider: str, ticket: Dict[str, Any], findings: List[Any]) -> str:
        max_findings = int(self.params.get("max_findings") or 30)
        safe_findings: List[Dict[str, Any]] = []
        for raw in findings[: max(1, max_findings)]:
            if not isinstance(raw, dict):
                continue
            safe_findings.append(
                {
                    "findingId": raw.get("findingId"),
                    "ruleId": raw.get("ruleId"),
                    "severity": raw.get("severity"),
                    "status": raw.get("status"),
                    "location": raw.get("location"),
                    "introducedBy": raw.get("introducedBy"),
                    "aiOpinion": raw.get("aiOpinion"),
                }
            )

        base = {
            "action": action,
            "provider": provider,
            "ticket": {
                "projectId": ticket.get("projectId"),
                "ticketProject": ticket.get("ticketProject"),
                "assigneeStrategy": ticket.get("assigneeStrategy"),
                "labels": ticket.get("labels"),
                "existingTitleI18n": ticket.get("titleI18n"),
                "existingDescriptionI18n": ticket.get("descriptionI18n"),
            },
            "findings": safe_findings,
        }
        return json.dumps(base, ensure_ascii=False, indent=2)

    def _maybe_call_llm(self, context: AgentContext, user_json: str) -> Optional[Dict[str, Any]]:
        extra = context.event.extra or {}
        payload = extra.get("payload") if isinstance(extra, dict) else None
        payload = payload if isinstance(payload, dict) else {}
        ai_client = payload.get("aiClient") if isinstance(payload.get("aiClient"), dict) else {}

        base_url = (ai_client.get("baseUrl") or os.getenv("SECRUX_AI_LLM_BASE_URL") or "").strip()
        api_key = (ai_client.get("apiKey") or os.getenv("SECRUX_AI_LLM_API_KEY") or "").strip()
        model = (ai_client.get("model") or os.getenv("SECRUX_AI_LLM_MODEL") or "").strip()
        if not base_url or not api_key or not model:
            return None

        url = base_url.rstrip("/")
        if not url.endswith("/v1"):
            url = f"{url}/v1"
        url = f"{url}/chat/completions"

        system = (
            "You generate ticket copy for security findings.\n"
            "Return ONLY valid JSON with keys:\n"
            "- titleI18n: {zh: string, en: string}\n"
            "- descriptionI18n: {zh: string, en: string}\n"
            "- labels: string[] (optional)\n"
            "Constraints:\n"
            "- zh must be Simplified Chinese; en must be English.\n"
            "- Keep rule IDs, file paths, and code identifiers unchanged.\n"
            "- Description should include: impact, reproduction hints (if possible), and fix guidance.\n"
        )

        body = {
            "model": model,
            "temperature": 0.2,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user_json},
            ],
        }
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

        job_id = str(extra.get("jobId")) if isinstance(extra, dict) and extra.get("jobId") is not None else None
        tenant_id = getattr(context.event, "tenant_id", None)
        target_id = getattr(context.event, "stage_id", None)
        mode = str(payload.get("action") or "generate")

        dump_llm_request(
            job_id=job_id,
            tenant_id=str(tenant_id) if tenant_id is not None else None,
            target_id=str(target_id) if target_id is not None else None,
            agent=getattr(self, "name", None),
            mode=mode,
            purpose="review",
            url=url,
            model=model,
            temperature=0.2,
            request_body=body,
        )

        try:
            with httpx.Client(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
                resp = client.post(url, json=body, headers=headers)
                resp.raise_for_status()
                data = resp.json()
                dump_llm_response(
                    job_id=job_id,
                    tenant_id=str(tenant_id) if tenant_id is not None else None,
                    target_id=str(target_id) if target_id is not None else None,
                    agent=getattr(self, "name", None),
                    mode=mode,
                    purpose="review",
                    url=url,
                    model=model,
                    response_json=data if isinstance(data, dict) else {"raw": data},
                    error=None,
                )
        except Exception as exc:
            dump_llm_response(
                job_id=job_id,
                tenant_id=str(tenant_id) if tenant_id is not None else None,
                target_id=str(target_id) if target_id is not None else None,
                agent=getattr(self, "name", None),
                mode=mode,
                purpose="review",
                url=url,
                model=model,
                response_json=None,
                error=str(exc),
            )
            return None

        content = (
            data.get("choices", [{}])[0]
            .get("message", {})
            .get("content")
        )
        if not isinstance(content, str) or not content.strip():
            return None
        parsed = self._extract_json(content)
        if not parsed:
            return None
        parsed["raw"] = content
        return parsed

    def _extract_json(self, text: str) -> Optional[Dict[str, Any]]:
        try:
            return json.loads(text)
        except Exception:
            pass
        start = text.find("{")
        end = text.rfind("}")
        if start == -1 or end == -1 or end <= start:
            return None
        try:
            return json.loads(text[start : end + 1])
        except Exception:
            return None

    def _fallback_copy(self, *, provider: str, findings: List[Any]) -> tuple[Dict[str, str], Dict[str, str]]:
        count = len(findings) if isinstance(findings, list) else 0
        title_en = f"Security findings ({count})"
        title_zh = f"安全漏洞（{count}）"
        desc_en = "Please review and remediate the following findings in the platform."
        desc_zh = "请在平台中复核并修复以下漏洞。"
        return {"en": title_en, "zh": title_zh}, {"en": desc_en, "zh": desc_zh}

