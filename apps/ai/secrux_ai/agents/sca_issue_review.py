from __future__ import annotations

import json
import os
import re
from typing import Any, Dict, List, Optional

import httpx

from ..debug.prompt_dump import dump_finding_payload, dump_llm_request, dump_llm_response
from ..models import AgentContext, AgentFinding, FindingStatus, Severity
from .base import BaseAgent


class ScaIssueReviewAgent(BaseAgent):
    """
    AI review agent for one SCA dependency issue.

    Goals:
    - Decide CONFIRMED vs FALSE_POSITIVE (or keep UNCERTAIN verdict) based on version and usage evidence.
    - Provide short bilingual summaries and fix hints.
    """

    def run(self, context: AgentContext) -> List[AgentFinding]:
        mode = str(self.params.get("mode") or "simple").lower()
        issue = self._extract_issue(context)
        if not issue:
            return [
                AgentFinding(
                    agent=self.name,
                    severity=Severity.INFO,
                    summary="No scaIssue payload provided to SCA review agent",
                    details={"mode": mode},
                )
            ]

        verdict, suggested_status, severity, confidence, opinion_i18n, summary, fix_hint = self._review(context, issue, mode)
        return [
            AgentFinding(
                agent=self.name,
                severity=severity,
                status=suggested_status,
                summary=summary,
                details={
                    "mode": mode,
                    "issue": self._redact_issue(issue),
                    "llm": {
                        "verdict": verdict,
                        "suggestedStatus": suggested_status.value,
                        "severity": severity.value,
                        "confidence": confidence,
                        "opinionI18n": opinion_i18n,
                        "summary": summary,
                        "fixHint": fix_hint,
                    },
                },
            )
        ]

    def _extract_issue(self, context: AgentContext) -> Optional[Dict[str, Any]]:
        extra = context.event.extra or {}
        issue = extra.get("scaIssue")
        if isinstance(issue, dict):
            return issue
        return None

    def _review(
        self,
        context: AgentContext,
        issue: Dict[str, Any],
        mode: str,
    ) -> tuple[str, FindingStatus, Severity, float, Dict[str, Any], str, Optional[str]]:
        verdict = "UNCERTAIN"
        suggested_status = FindingStatus.OPEN
        base_severity = self._parse_severity(((issue.get("severity") or "") if isinstance(issue.get("severity"), str) else "") or "INFO")
        severity = base_severity
        confidence = 0.6

        prompt = self._build_prompt(issue)
        llm_output = self._call_llm(context, prompt, mode)
        if llm_output:
            verdict = self._normalize_verdict(llm_output.get("verdict"))
            severity = self._parse_severity(llm_output.get("severity") or base_severity.value)
            confidence = self._normalize_confidence(llm_output.get("confidence"), default=0.65)
            suggested_status = self._normalize_status(llm_output.get("suggestedStatus")) or suggested_status
            opinion_i18n = self._normalize_opinion_i18n(llm_output.get("opinionI18n"), issue)
            summary = self._pick_summary(llm_output, opinion_i18n, issue)
            fix_hint = self._pick_fix_hint(llm_output, opinion_i18n)
            return verdict, suggested_status, severity, confidence, opinion_i18n, summary, fix_hint

        opinion_i18n = {
            "zh": {"summary": "未配置 LLM，无法进行 SCA AI 复核。", "fixHint": None},
            "en": {"summary": "LLM is not configured; SCA AI review was skipped.", "fixHint": None},
        }
        summary = opinion_i18n["en"]["summary"]
        return verdict, suggested_status, severity, confidence, opinion_i18n, summary, None

    def _build_prompt(self, issue: Dict[str, Any]) -> str:
        vuln_id = issue.get("vulnId") or issue.get("vuln_id") or "N/A"
        pkg = issue.get("packageName") or issue.get("componentName") or "N/A"
        installed = issue.get("installedVersion") or issue.get("componentVersion") or ""
        fixed = issue.get("fixedVersion") or ""
        purl = issue.get("componentPurl") or ""
        primary = issue.get("primaryUrl") or ""
        evidence = issue.get("evidence") if isinstance(issue.get("evidence"), dict) else {}
        title = evidence.get("title") if isinstance(evidence.get("title"), str) else ""
        description = evidence.get("description") if isinstance(evidence.get("description"), str) else ""
        cvss = evidence.get("cvss") if isinstance(evidence.get("cvss"), dict) else None
        usage = issue.get("usageEvidence") if isinstance(issue.get("usageEvidence"), dict) else {}
        usage_entries = usage.get("entries") if isinstance(usage.get("entries"), list) else []

        parts: List[str] = [
            f"[Vulnerability] {vuln_id}",
            f"[Package] {pkg}{'@' + str(installed) if installed else ''}{(' -> ' + str(fixed)) if fixed else ''}",
        ]
        if purl:
            parts.append(f"[PURL] {purl}")
        if primary:
            parts.append(f"[Reference] {primary}")
        if title:
            parts.append(f"[Title] {title}")
        if description:
            parts.append("[Description]")
            parts.append(str(description)[:2000])
        if cvss:
            parts.append("[CVSS]")
            parts.append(json.dumps(cvss, ensure_ascii=False)[:2000])

        parts.append("[Task] Based on the package version and the usage evidence, determine if this SCA issue is a true positive or false positive. Provide concise fix hints if confirmed.")

        if usage_entries:
            parts.append("[Usage evidence]")
            shown = 0
            for item in usage_entries:
                if not isinstance(item, dict):
                    continue
                file = item.get("file") or "unknown"
                line = item.get("line") or ""
                kind = item.get("kind") or ""
                snippet = item.get("snippet") or ""
                snippet = str(snippet).strip().replace("\t", " ")[:300]
                parts.append(f"- {file}:{line} {kind} {snippet}")
                shown += 1
                if shown >= 30:
                    break
        else:
            parts.append("[Usage evidence] <none found>")

        return "\n".join(parts)

    def _call_llm(self, context: AgentContext, prompt: str, mode: str) -> Optional[Dict[str, Any]]:
        extra = context.event.extra or {}
        job_id = extra.get("jobId")
        target_id = getattr(context.event, "stage_id", None)

        dump_finding_payload(
            job_id=str(job_id) if job_id is not None else None,
            tenant_id=getattr(context.event, "tenant_id", None),
            target_id=target_id,
            agent=getattr(self, "name", None),
            mode=mode,
            purpose="review",
            finding=extra.get("scaIssue") if isinstance(extra.get("scaIssue"), dict) else None,
        )

        ai_client = (context.event.extra or {}).get("aiClient") if context.event and context.event.extra else None
        if not isinstance(ai_client, dict):
            ai_client = {}
        base_url = (ai_client.get("baseUrl") or os.getenv("SECRUX_AI_LLM_BASE_URL") or "").strip()
        api_key = (ai_client.get("apiKey") or os.getenv("SECRUX_AI_LLM_API_KEY") or "").strip()
        model = (ai_client.get("model") or os.getenv("SECRUX_AI_LLM_MODEL") or "").strip()
        if not base_url or not api_key or not model:
            return None

        url = base_url.rstrip("/")
        if re.search(r"/chat/completions[^/]*$", url):
            pass
        elif re.search(r"/v\\d+$", url):
            url = f"{url}/chat/completions"
        else:
            url = f"{url}/v1/chat/completions"

        temperature = 0.2
        system = (
            "You are a security engineer reviewing one dependency vulnerability (SCA issue). "
            "Use the package version and the usage evidence to judge whether the issue is truly present in the project. "
            "Return ONLY valid JSON with keys: "
            "verdict (TRUE_POSITIVE|FALSE_POSITIVE|UNCERTAIN), "
            "suggestedStatus (CONFIRMED|FALSE_POSITIVE), "
            "severity (CRITICAL|HIGH|MEDIUM|LOW|INFO), "
            "confidence (0-1), "
            "opinionI18n (object with keys zh/en; each has summary, fixHint, optional rationale; "
            "zh must be Simplified Chinese, en must be English), "
            "summary (string, default to opinionI18n.en.summary), "
            "fixHint (string, default to opinionI18n.en.fixHint)."
        )
        request_body = {
            "model": model,
            "temperature": temperature,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt},
            ],
        }

        dump_llm_request(
            job_id=str(job_id) if job_id is not None else None,
            tenant_id=getattr(context.event, "tenant_id", None),
            target_id=target_id,
            agent=getattr(self, "name", None),
            mode=mode,
            purpose="review",
            url=url,
            model=model,
            temperature=temperature,
            request_body=request_body,
        )

        try:
            with httpx.Client(timeout=90) as client:
                resp = client.post(url, headers={"Authorization": f"Bearer {api_key}"}, json=request_body)
                resp.raise_for_status()
                data = resp.json()
        except Exception as exc:
            dump_llm_response(
                job_id=str(job_id) if job_id is not None else None,
                tenant_id=getattr(context.event, "tenant_id", None),
                target_id=target_id,
                agent=getattr(self, "name", None),
                mode=mode,
                purpose="review",
                url=url,
                model=model,
                response_json=None,
                error=str(exc),
            )
            return None

        dump_llm_response(
            job_id=str(job_id) if job_id is not None else None,
            tenant_id=getattr(context.event, "tenant_id", None),
            target_id=target_id,
            agent=getattr(self, "name", None),
            mode=mode,
            purpose="review",
            url=url,
            model=model,
            response_json=data if isinstance(data, dict) else None,
            error=None,
        )

        content = None
        if isinstance(data, dict):
            choices = data.get("choices")
            if isinstance(choices, list) and choices:
                msg = choices[0].get("message") if isinstance(choices[0], dict) else None
                content = msg.get("content") if isinstance(msg, dict) else None
        if not isinstance(content, str) or not content.strip():
            return None

        parsed = self._extract_json(content)
        if not parsed:
            return None
        return parsed

    def _extract_json(self, content: str) -> Optional[Dict[str, Any]]:
        text = content.strip()
        if text.startswith("```"):
            text = "\n".join([line for line in text.splitlines() if not line.strip().startswith("```")]).strip()
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end < 0 or end <= start:
            return None
        blob = text[start : end + 1]
        try:
            value = json.loads(blob)
            return value if isinstance(value, dict) else None
        except Exception:
            return None

    def _normalize_verdict(self, value: Any) -> str:
        if not isinstance(value, str):
            return "UNCERTAIN"
        v = value.strip().upper()
        if v in ("TRUE_POSITIVE", "FALSE_POSITIVE", "UNCERTAIN"):
            return v
        return "UNCERTAIN"

    def _normalize_status(self, value: Any) -> Optional[FindingStatus]:
        if not isinstance(value, str):
            return None
        v = value.strip().upper()
        if v == "CONFIRMED":
            return FindingStatus.CONFIRMED
        if v == "FALSE_POSITIVE":
            return FindingStatus.FALSE_POSITIVE
        return None

    def _normalize_confidence(self, value: Any, default: float) -> float:
        try:
            num = float(value)
        except Exception:
            return default
        if num < 0:
            return 0.0
        if num > 1:
            return 1.0
        return num

    def _parse_severity(self, value: Any) -> Severity:
        if isinstance(value, Severity):
            return value
        if not isinstance(value, str):
            return Severity.INFO
        v = value.strip().upper()
        try:
            return Severity(v)
        except Exception:
            return Severity.INFO

    def _normalize_opinion_i18n(self, value: Any, issue: Dict[str, Any]) -> Dict[str, Any]:
        if not isinstance(value, dict):
            value = {}
        zh = value.get("zh") if isinstance(value.get("zh"), dict) else {}
        en = value.get("en") if isinstance(value.get("en"), dict) else {}
        if not zh:
            zh = {"summary": f"依赖漏洞 {issue.get('vulnId') or 'N/A'} 复核完成。", "fixHint": None}
        if not en:
            en = {"summary": f"SCA issue {issue.get('vulnId') or 'N/A'} review completed.", "fixHint": None}
        return {"zh": zh, "en": en}

    def _pick_summary(self, llm_output: Dict[str, Any], opinion_i18n: Dict[str, Any], issue: Dict[str, Any]) -> str:
        if isinstance(llm_output.get("summary"), str) and llm_output.get("summary").strip():
            return llm_output.get("summary").strip()
        en = opinion_i18n.get("en") if isinstance(opinion_i18n.get("en"), dict) else {}
        if isinstance(en.get("summary"), str) and en.get("summary").strip():
            return en.get("summary").strip()
        return f"SCA issue {issue.get('vulnId') or 'N/A'} review completed."

    def _pick_fix_hint(self, llm_output: Dict[str, Any], opinion_i18n: Dict[str, Any]) -> Optional[str]:
        if isinstance(llm_output.get("fixHint"), str) and llm_output.get("fixHint").strip():
            return llm_output.get("fixHint").strip()
        en = opinion_i18n.get("en") if isinstance(opinion_i18n.get("en"), dict) else {}
        if isinstance(en.get("fixHint"), str) and en.get("fixHint").strip():
            return en.get("fixHint").strip()
        return None

    def _redact_issue(self, issue: Dict[str, Any]) -> Dict[str, Any]:
        safe = dict(issue)
        for key in ("aiClient", "apiKey", "api_key"):
            safe.pop(key, None)
        return safe
