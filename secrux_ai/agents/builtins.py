from __future__ import annotations

from typing import List

from ..models import AgentContext, AgentFinding, FindingStatus, Severity
from .base import BaseAgent


class SignalAwareAgent(BaseAgent):
    """Emits findings based on stage signals (needs_ai_review, risk delta, etc.)."""

    def run(self, context: AgentContext) -> List[AgentFinding]:
        findings: List[AgentFinding] = []
        signals = context.event.signals
        if not signals.needs_ai_review:
            findings.append(
                AgentFinding(
                    agent=self.name,
                    severity=Severity.INFO,
                    status=FindingStatus.CONFIRMED,
                    summary="No AI action required for this stage",
                    details={"reason": "needs_ai_review flag is false"},
                )
            )
            return findings

        if signals.risk_delta and signals.risk_delta > 0.8:
            findings.append(
                AgentFinding(
                    agent=self.name,
                    severity=Severity.HIGH,
                    summary="Stage indicates high risk delta",
                    details={"risk_delta": signals.risk_delta},
                )
            )
        elif signals.auto_fix_possible:
            findings.append(
                AgentFinding(
                    agent=self.name,
                    severity=Severity.LOW,
                    summary="Auto-fix is possible based on stage hints",
                    details={"auto_fix_possible": True},
                )
            )
        return findings


class LogHeuristicsAgent(BaseAgent):
    """Lightweight heuristics scanning for common engine issues inside log excerpts."""

    KEYWORDS = {
        "timeout": Severity.HIGH,
        "out of memory": Severity.HIGH,
        "retrying": Severity.MEDIUM,
        "no findings": Severity.LOW,
    }
    ORDER = {
        Severity.CRITICAL: 5,
        Severity.HIGH: 4,
        Severity.MEDIUM: 3,
        Severity.LOW: 2,
        Severity.INFO: 1,
    }

    def run(self, context: AgentContext) -> List[AgentFinding]:
        summary = []
        severity = Severity.INFO
        for line in context.event.log_excerpt:
            lower = line.lower()
            for keyword, sev in self.KEYWORDS.items():
                if keyword in lower:
                    summary.append(line.strip())
                    if self.ORDER[sev] > self.ORDER[severity]:
                        severity = sev
        if not summary:
            return []
        return [
            AgentFinding(
                agent=self.name,
                severity=severity,
                summary=" | ".join(summary[:3]),
                details={"matches": summary},
            )
        ]


class McpReviewAgent(BaseAgent):
    """Delegates to an MCP tool to produce a structured review."""

    def run(self, context: AgentContext) -> List[AgentFinding]:
        client = context.mcp_client
        if client is None:
            return [
                AgentFinding(
                    agent=self.name,
                    severity=Severity.INFO,
                    summary="MCP client not configured; skipping review",
                    details={},
                )
            ]

        payload = {
            "taskId": context.event.task_id,
            "stageId": context.event.stage_id,
            "stageType": context.event.stage_type.value,
            "tenantId": context.event.tenant_id,
            "logExcerpt": context.event.log_excerpt,
            "signals": context.event.signals.model_dump(by_alias=True),
        }
        tool_name = self.params.get("tool", "stage-reviewer")
        result = client.invoke_tool(tool_name, payload)
        summary = result.get("summary", "AI review completed")
        severity_value = result.get("severity", "INFO")
        try:
            severity = Severity(severity_value)
        except ValueError:
            severity = Severity.INFO
        details = result.get("details", result)
        return [
            AgentFinding(
                agent=self.name,
                severity=severity,
                summary=summary,
                details=details,
            )
        ]
