from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Optional

import httpx
from tree_sitter_languages import get_parser

from ..models import AgentContext, AgentFinding, FindingStatus, Severity
from ..debug.prompt_dump import dump_llm_request, dump_llm_response, dump_finding_payload
from .base import BaseAgent


LANG_MAP = {
    ".js": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".jsx": "javascript",
    ".py": "python",
    ".java": "java",
    ".go": "go",
    ".rb": "ruby",
    ".rs": "rust",
    ".c": "c",
    ".cpp": "cpp",
    ".cc": "cpp",
    ".cs": "c_sharp",
    ".php": "php",
}


class VulnReviewAgent(BaseAgent):
    """
    AI vulnerability review agent with two modes:
    - simple: use sink-near code snippet + metadata
    - precise: use SARIF-style dataflow + optional AST call extraction
    """

    def run(self, context: AgentContext) -> List[AgentFinding]:
        mode = (self.params.get("mode") or "simple").lower()
        finding = self._extract_finding(context)
        if not finding:
            return [
                AgentFinding(
                    agent=self.name,
                    severity=Severity.INFO,
                    summary="No finding payload provided to AI review agent",
                    details={"mode": mode},
                )
            ]

        if mode == "precise":
            return [self._run_precise(context, finding)]
        return [self._run_simple(context, finding)]

    def _extract_finding(self, context: AgentContext) -> Optional[Dict[str, Any]]:
        extra = context.event.extra or {}
        finding = extra.get("finding")
        if isinstance(finding, dict):
            return finding
        # fallback: if entire event represents a single finding (custom payload)
        return None

    def _run_simple(self, context: AgentContext, finding: Dict[str, Any]) -> AgentFinding:
        snippet = finding.get("codeSnippet") or {}
        location = finding.get("location") or {}
        rule_id = finding.get("ruleId") or finding.get("rule_id")
        severity = self._parse_severity(finding.get("severity"))
        code_text = self._format_snippet(snippet)
        summary = f"Quick AI review for rule {rule_id or 'N/A'}"
        prompt_parts = [
            f"[Rule] {rule_id or 'N/A'} | Severity {severity.value}",
            f"[Location] {location.get('path','unknown')}:{location.get('line') or location.get('startLine') or ''}",
            "[Task] Provide a concise assessment: real issue vs false positive, and a short fix hint.",
            "[Code snippet]",
            code_text or "<no snippet provided>",
        ]
        llm_output = self._maybe_call_llm(context, "\n".join(prompt_parts))
        if llm_output:
            suggested_status = self._status_from_llm(llm_output)
            opinion_i18n = llm_output.get("opinionI18n") if isinstance(llm_output.get("opinionI18n"), dict) else None
            summary_i18n = None
            if opinion_i18n:
                en = opinion_i18n.get("en") if isinstance(opinion_i18n.get("en"), dict) else None
                summary_i18n = en.get("summary") if en else None
            return AgentFinding(
                agent=self.name,
                severity=self._parse_severity(llm_output.get("severity") or severity.value),
                status=suggested_status or FindingStatus.OPEN,
                summary=str(summary_i18n or llm_output.get("summary") or summary),
                details={
                    "mode": "simple",
                    "ruleId": rule_id,
                    "location": location,
                    "llm": {k: v for k, v in llm_output.items() if k != "raw"},
                    "snippet": code_text,
                },
            )
        details = {
            "mode": "simple",
            "ruleId": rule_id,
            "severity": severity.value,
            "location": location,
            "snippet": code_text,
            "opinionI18n": {
                "zh": {"summary": f"快速 AI 复核：规则 {rule_id or 'N/A'}", "fixHint": None},
                "en": {"summary": summary, "fixHint": None},
            },
        }
        return AgentFinding(
            agent=self.name,
            severity=severity,
            status=FindingStatus.OPEN,
            summary=summary,
            details=details,
        )

    def _run_precise(self, context: AgentContext, finding: Dict[str, Any]) -> AgentFinding:
        dataflow = finding.get("dataflow") or finding.get("dataFlow") or {}
        nodes = dataflow.get("nodes") or []
        edges = dataflow.get("edges") or []
        call_chain = self._format_call_chain(nodes, edges)
        snippet = finding.get("codeSnippet") or {}
        location = finding.get("location") or {}
        rule_id = finding.get("ruleId") or finding.get("rule_id")
        severity = self._parse_severity(finding.get("severity"))
        enrichment_text = self._format_enrichment(finding.get("enrichment"))

        # AST extraction on snippet (best-effort)
        ast_depth = int(self.params.get("ast_depth") or 0)
        ast_calls: List[str] = []
        if ast_depth > 0:
            code_text = self._format_snippet(snippet, with_line_numbers=False)
            ast_calls = self._extract_calls(code_text, location.get("path", ""))

        summary = f"Precise AI review for rule {rule_id or 'N/A'}"
        prompt_parts = [
            f"[Rule] {rule_id or 'N/A'} | Severity {severity.value}",
            f"[Location] {location.get('path','unknown')}:{location.get('line') or location.get('startLine') or ''}",
            "[Task] Decide true positive vs false positive, and give fix hint. Use the call chain if present.",
            "[Call chain]",
            "\n".join(call_chain) or "<no call chain>",
            "[Code snippet]",
            self._format_snippet(snippet) or "<no snippet provided>",
        ]
        if enrichment_text:
            prompt_parts.extend(["[Enrichment]", enrichment_text])
        llm_output = self._maybe_call_llm(context, "\n".join(prompt_parts))
        if llm_output:
            suggested_status = self._status_from_llm(llm_output)
            opinion_i18n = llm_output.get("opinionI18n") if isinstance(llm_output.get("opinionI18n"), dict) else None
            summary_i18n = None
            if opinion_i18n:
                en = opinion_i18n.get("en") if isinstance(opinion_i18n.get("en"), dict) else None
                summary_i18n = en.get("summary") if en else None
            return AgentFinding(
                agent=self.name,
                severity=self._parse_severity(llm_output.get("severity") or severity.value),
                status=suggested_status or FindingStatus.OPEN,
                summary=str(summary_i18n or llm_output.get("summary") or summary),
                details={
                    "mode": "precise",
                    "ruleId": rule_id,
                    "location": location,
                    "callChain": call_chain,
                    "astCalls": ast_calls,
                    "llm": {k: v for k, v in llm_output.items() if k != "raw"},
                    "snippet": self._format_snippet(snippet),
                },
            )
        details = {
            "mode": "precise",
            "ruleId": rule_id,
            "severity": severity.value,
            "location": location,
            "callChain": call_chain,
            "astCalls": ast_calls,
            "snippet": self._format_snippet(snippet),
            "opinionI18n": {
                "zh": {"summary": f"精确 AI 复核：规则 {rule_id or 'N/A'}", "fixHint": None},
                "en": {"summary": summary, "fixHint": None},
            },
        }
        return AgentFinding(
            agent=self.name,
            severity=severity,
            status=FindingStatus.OPEN,
            summary=summary,
            details=details,
        )

    def _maybe_call_llm(self, context: AgentContext, prompt: str) -> Optional[Dict[str, Any]]:
        # Optional debug dump of the raw finding payload (works even when live LLM calls are disabled).
        extra = context.event.extra or {}
        job_id = extra.get("jobId") if isinstance(extra, dict) else None
        mode_from_event = extra.get("mode") if isinstance(extra, dict) else None
        finding_payload = extra.get("finding") if isinstance(extra, dict) else None
        dump_finding_payload(
            job_id=str(job_id) if job_id is not None else None,
            tenant_id=getattr(context.event, "tenant_id", None),
            target_id=getattr(context.event, "stage_id", None),
            agent=getattr(self, "name", None),
            mode=str(mode_from_event) if mode_from_event is not None else None,
            finding=finding_payload if isinstance(finding_payload, dict) else None,
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
        if not url.endswith("/v1"):
            url = f"{url}/v1"
        url = f"{url}/chat/completions"

        system = (
            "You are a security engineer reviewing one static-analysis finding. "
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
        data = self._call_chat_completion(
            context=context,
            purpose="review",
            url=url,
            api_key=api_key,
            model=model,
            system=system,
            user=prompt,
        )
        if not data:
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
        parsed = self._normalize_llm_output(parsed)
        parsed = self._ensure_bilingual(
            context=context,
            url=url,
            api_key=api_key,
            model=model,
            parsed=parsed,
        )
        parsed["raw"] = content
        return parsed

    def _status_from_llm(self, llm_output: Dict[str, Any]) -> Optional[FindingStatus]:
        verdict = llm_output.get("verdict")
        if verdict == "TRUE_POSITIVE":
            return FindingStatus.CONFIRMED
        if verdict == "FALSE_POSITIVE":
            return FindingStatus.FALSE_POSITIVE
        suggested = self._parse_status(llm_output.get("suggestedStatus"))
        if suggested in (FindingStatus.CONFIRMED, FindingStatus.FALSE_POSITIVE):
            return suggested
        return None

    def _format_enrichment(self, enrichment: Any) -> str:
        if not isinstance(enrichment, dict) or not enrichment:
            return ""

        def truncate(value: Any, limit: int) -> Any:
            if isinstance(value, str):
                return value if len(value) <= limit else value[:limit] + "…"
            return value

        primary = enrichment.get("primary") if isinstance(enrichment.get("primary"), dict) else {}
        method = primary.get("method") if isinstance(primary.get("method"), dict) else {}
        conditions = primary.get("conditions") if isinstance(primary.get("conditions"), list) else []
        invocations = primary.get("invocations") if isinstance(primary.get("invocations"), list) else []
        field_defs = enrichment.get("fieldDefinitions") if isinstance(enrichment.get("fieldDefinitions"), list) else []
        node_methods = (
            enrichment.get("dataflow", {}).get("nodeMethods")
            if isinstance(enrichment.get("dataflow"), dict)
            else []
        )
        if not isinstance(node_methods, list):
            node_methods = []

        compact = {
            "primary": {
                "path": primary.get("path"),
                "line": primary.get("line"),
                "method": {
                    "signature": truncate(method.get("signature"), 300),
                    "startLine": method.get("startLine"),
                    "endLine": method.get("endLine"),
                },
                "conditions": [
                    {
                        "line": c.get("line"),
                        "text": truncate(c.get("text"), 300),
                        "externalSymbols": (c.get("externalSymbols") or [])[:10],
                    }
                    for c in conditions[:15]
                    if isinstance(c, dict)
                ],
                "extraInvocations": [
                    {
                        "line": inv.get("line"),
                        "text": truncate(inv.get("text"), 260),
                        "argIdentifiers": (inv.get("argIdentifiers") or [])[:10],
                    }
                    for inv in invocations[:25]
                    if isinstance(inv, dict) and not inv.get("inDataflow")
                ],
            },
            "dataflowNodeMethods": [
                {
                    "nodeId": nm.get("nodeId"),
                    "label": nm.get("label"),
                    "path": nm.get("path"),
                    "line": nm.get("line"),
                    "methodSignature": truncate((nm.get("method") or {}).get("signature"), 220)
                    if isinstance(nm.get("method"), dict)
                    else None,
                    "nearby": {
                        "conditions": [
                            {"line": c.get("line"), "text": truncate(c.get("text"), 260), "externalSymbols": (c.get("externalSymbols") or [])[:10]}
                            for c in (nm.get("neighborhood") or {}).get("conditions", [])[:8]
                            if isinstance(c, dict)
                        ],
                        "invocations": [
                            {"line": inv.get("line"), "text": truncate(inv.get("text"), 240), "argIdentifiers": (inv.get("argIdentifiers") or [])[:10]}
                            for inv in (nm.get("neighborhood") or {}).get("invocations", [])[:10]
                            if isinstance(inv, dict)
                        ],
                        "fieldDefinitions": [
                            {"name": fd.get("name"), "line": fd.get("line"), "text": truncate(fd.get("text"), 320)}
                            for fd in (nm.get("neighborhood") or {}).get("fieldDefinitions", [])[:6]
                            if isinstance(fd, dict)
                        ],
                    },
                }
                for nm in node_methods[:8]
                if isinstance(nm, dict)
            ],
            "fieldDefinitions": [
                {"name": fd.get("name"), "line": fd.get("line"), "text": truncate(fd.get("text"), 400)}
                for fd in field_defs[:10]
                if isinstance(fd, dict)
            ],
        }
        try:
            return json.dumps(compact, ensure_ascii=False, indent=2)
        except Exception:
            return str(compact)

    def _call_chat_completion(
        self,
        context: Optional[AgentContext],
        purpose: str,
        url: str,
        api_key: str,
        model: str,
        system: str,
        user: str,
        temperature: float = 0.2,
    ) -> Optional[Dict[str, Any]]:
        body = {
            "model": model,
            "temperature": temperature,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        headers = {"Authorization": f"Bearer {api_key}"}
        job_id = None
        tenant_id = None
        target_id = None
        mode = None
        if context is not None and context.event:
            extra = context.event.extra or {}
            if isinstance(extra, dict):
                job_id = extra.get("jobId")
                mode = extra.get("mode")
            tenant_id = getattr(context.event, "tenant_id", None)
            target_id = getattr(context.event, "stage_id", None)
        dump_llm_request(
            job_id=str(job_id) if job_id is not None else None,
            tenant_id=str(tenant_id) if tenant_id is not None else None,
            target_id=str(target_id) if target_id is not None else None,
            agent=getattr(self, "name", None),
            mode=str(mode) if mode is not None else None,
            purpose=purpose,
            url=url,
            model=model,
            temperature=temperature,
            request_body=body,
        )
        try:
            with httpx.Client(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
                resp = client.post(url, json=body, headers=headers)
                resp.raise_for_status()
                data = resp.json()
                dump_llm_response(
                    job_id=str(job_id) if job_id is not None else None,
                    tenant_id=str(tenant_id) if tenant_id is not None else None,
                    target_id=str(target_id) if target_id is not None else None,
                    agent=getattr(self, "name", None),
                    mode=str(mode) if mode is not None else None,
                    purpose=purpose,
                    url=url,
                    model=model,
                    response_json=data if isinstance(data, dict) else {"raw": data},
                    error=None,
                )
                return data
        except Exception as exc:
            dump_llm_response(
                job_id=str(job_id) if job_id is not None else None,
                tenant_id=str(tenant_id) if tenant_id is not None else None,
                target_id=str(target_id) if target_id is not None else None,
                agent=getattr(self, "name", None),
                mode=str(mode) if mode is not None else None,
                purpose=purpose,
                url=url,
                model=model,
                response_json=None,
                error=str(exc),
            )
            return None

    def _normalize_llm_output(self, parsed: Dict[str, Any]) -> Dict[str, Any]:
        opinion = parsed.get("opinionI18n")
        if isinstance(opinion, dict):
            zh = opinion.get("zh") if isinstance(opinion.get("zh"), dict) else None
            en = opinion.get("en") if isinstance(opinion.get("en"), dict) else None
            if isinstance(opinion.get("en"), dict):
                if not isinstance(parsed.get("summary"), str) or not str(parsed.get("summary")).strip():
                    parsed["summary"] = opinion["en"].get("summary")
                if not isinstance(parsed.get("fixHint"), str) or not str(parsed.get("fixHint")).strip():
                    parsed["fixHint"] = opinion["en"].get("fixHint")
        return parsed

    def _ensure_bilingual(
        self,
        context: Optional[AgentContext],
        url: str,
        api_key: str,
        model: str,
        parsed: Dict[str, Any],
    ) -> Dict[str, Any]:
        opinion = parsed.get("opinionI18n")
        if not isinstance(opinion, dict):
            return parsed

        def to_text(value: Any) -> Dict[str, str]:
            if not isinstance(value, dict):
                return {}
            out: Dict[str, str] = {}
            for key in ("summary", "fixHint", "rationale"):
                v = value.get(key)
                if isinstance(v, str) and v.strip():
                    out[key] = v.strip()
            return out

        zh = to_text(opinion.get("zh"))
        en = to_text(opinion.get("en"))

        # If one side is missing, try to translate from the other side.
        if not en and zh:
            translated = self._translate_opinion(context, url, api_key, model, source_lang="zh", target_lang="en", text=zh)
            if translated:
                opinion["en"] = translated
                en = translated
        if not zh and en:
            translated = self._translate_opinion(context, url, api_key, model, source_lang="en", target_lang="zh", text=en)
            if translated:
                opinion["zh"] = translated
                zh = translated

        # If zh exists but is identical to en, attempt a real Chinese translation.
        if zh and en and zh.get("summary") == en.get("summary") and zh.get("fixHint") == en.get("fixHint"):
            translated = self._translate_opinion(context, url, api_key, model, source_lang="en", target_lang="zh", text=en)
            if translated:
                opinion["zh"] = translated

        parsed["opinionI18n"] = opinion
        if isinstance(opinion.get("en"), dict):
            parsed["summary"] = opinion["en"].get("summary") or parsed.get("summary")
            parsed["fixHint"] = opinion["en"].get("fixHint") or parsed.get("fixHint")
        return parsed

    def _translate_opinion(
        self,
        context: Optional[AgentContext],
        url: str,
        api_key: str,
        model: str,
        source_lang: str,
        target_lang: str,
        text: Dict[str, str],
    ) -> Optional[Dict[str, str]]:
        if not text:
            return None
        system = (
            "You are a translator for security review notes. "
            "Return ONLY valid JSON with keys: summary, fixHint, optional rationale. "
            f"Translate from {source_lang} to {target_lang}. "
            "Keep code identifiers, URLs, and enum values unchanged."
        )
        user = json.dumps(text, ensure_ascii=False)
        data = self._call_chat_completion(
            context=context,
            purpose="translate_opinion",
            url=url,
            api_key=api_key,
            model=model,
            system=system,
            user=user,
            temperature=0.1,
        )
        if not data:
            return None
        content = (
            data.get("choices", [{}])[0]
            .get("message", {})
            .get("content")
        )
        if not isinstance(content, str) or not content.strip():
            return None
        parsed = self._extract_json(content)
        if not isinstance(parsed, dict):
            return None
        out = {}
        for key in ("summary", "fixHint", "rationale"):
            v = parsed.get(key)
            if isinstance(v, str) and v.strip():
                out[key] = v.strip()
        return out or None

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

    def _parse_status(self, value: Any) -> Optional[FindingStatus]:
        if not isinstance(value, str):
            return None
        try:
            return FindingStatus(value)
        except Exception:
            return None

    def _format_snippet(self, snippet: Dict[str, Any], with_line_numbers: bool = True) -> str:
        lines = snippet.get("lines") or []
        formatted: List[str] = []
        for line in lines:
            content = line.get("content", "")
            ln = line.get("lineNumber")
            prefix = f"{ln}: " if with_line_numbers and ln is not None else ""
            marker = ">> " if line.get("highlight") else ""
            formatted.append(f"{marker}{prefix}{content}")
        return "\n".join(formatted)

    def _format_call_chain(self, nodes: List[Dict[str, Any]], edges: List[Dict[str, Any]]) -> List[str]:
        # naive path rendering: just list nodes in order; if edges present, sort by source-target appearance
        if not nodes:
            return []
        ordered = nodes
        lines = []
        for idx, node in enumerate(ordered):
            label = node.get("label") or node.get("id") or f"step-{idx}"
            loc = f"{node.get('file','unknown')}:{node.get('line','')}"
            lines.append(f"{idx+1}) {label} @ {loc}")
        return lines

    def _extract_calls(self, code: str, path: str) -> List[str]:
        if not code:
            return []
        lang = LANG_MAP.get(os.path.splitext(path)[1].lower(), "python")
        try:
            parser = get_parser(lang)
        except Exception:
            return []
        tree = parser.parse(code.encode())
        root = tree.root_node
        calls: List[str] = []
        self._walk_calls(root, code, calls)
        return calls[:50]

    def _walk_calls(self, node, code: str, out: List[str]) -> None:
        if node.type in ("call_expression", "function_call", "method_invocation"):
            text = code[node.start_byte:node.end_byte]
            out.append(text.strip())
        for child in node.children:
            self._walk_calls(child, code, out)

    def _parse_severity(self, value: Any) -> Severity:
        try:
            return Severity(value)
        except Exception:
            return Severity.INFO
