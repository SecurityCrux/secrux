from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Dict, List, Optional

from .agents.base import BaseAgent
from .agents.builtins import LogHeuristicsAgent, McpReviewAgent, SignalAwareAgent
from .agents.sca_issue_review import ScaIssueReviewAgent
from .agents.ticket_copy import TicketCopyAgent
from .agents.vuln_review import VulnReviewAgent
from .callbacks import CallbackSink, StdoutCallbackSink
from .config import AgentConfig, CallbackConfig, PlatformConfig
from .mcp import BaseMCPClient, build_mcp_client
from .models import AgentContext, AgentRecommendation, StageEvent
from .utils import load_from_entrypoint


BUILTIN_AGENTS = {
    "signal": SignalAwareAgent,
    "log": LogHeuristicsAgent,
    "mcp-review": McpReviewAgent,
    "vuln-review": VulnReviewAgent,
    "sca-issue-review": ScaIssueReviewAgent,
    "ticket-copy": TicketCopyAgent,
}


@dataclass
class AgentRuntime:
    config: AgentConfig
    instance: BaseAgent
    mcp_profile: Optional[str] = None


class AgentOrchestrator:
    def __init__(self, config: PlatformConfig, callback_sink: Optional[CallbackSink] = None):
        self.config = config
        self.callback_sink = callback_sink or self._build_callback_sink(config.callbacks)
        self.mcp_clients: Dict[str, BaseMCPClient] = {}
        self.agents: List[AgentRuntime] = []
        self._build_mcp_clients()
        self._build_agents()

    def _build_callback_sink(self, callback_config: CallbackConfig) -> CallbackSink:
        from .callbacks import FileCallbackSink, WebhookCallbackSink

        if callback_config.mode == "webhook":
            if not callback_config.endpoint:
                raise ValueError("Webhook callback requires endpoint")
            return WebhookCallbackSink(callback_config.endpoint, callback_config.headers, callback_config.token)
        if callback_config.mode == "file":
            if not callback_config.file_path:
                raise ValueError("File callback requires file_path")
            return FileCallbackSink(callback_config.file_path)
        return StdoutCallbackSink()

    def _build_mcp_clients(self) -> None:
        for profile in self.config.mcp_profiles:
            self.mcp_clients[profile.name] = build_mcp_client(profile)

    def _build_agents(self) -> None:
        for agent_cfg in self.config.agents:
            if not agent_cfg.enabled:
                continue
            cls = self._resolve_agent_class(agent_cfg)
            instance = cls(
                name=agent_cfg.name,
                params=agent_cfg.params,
                stage_types=agent_cfg.stage_types,
            )
            runtime = AgentRuntime(config=agent_cfg, instance=instance, mcp_profile=agent_cfg.mcp_profile)
            self.agents.append(runtime)

    def _resolve_agent_class(self, agent_cfg: AgentConfig):
        if agent_cfg.entrypoint:
            return load_from_entrypoint(agent_cfg.entrypoint)
        if agent_cfg.kind in BUILTIN_AGENTS:
            return BUILTIN_AGENTS[agent_cfg.kind]
        raise ValueError(f"Unknown agent kind '{agent_cfg.kind}' for agent '{agent_cfg.name}'")

    def _resolve_mcp_client(self, profile_name: Optional[str]) -> Optional[BaseMCPClient]:
        if not profile_name:
            return None
        return self.mcp_clients.get(profile_name)

    def process(self, event: StageEvent) -> AgentRecommendation:
        shared_cache: Dict[str, object] = {}
        findings = []
        start = time.perf_counter()
        for runtime in self.agents:
            context = AgentContext(
                event=event,
                shared_cache=shared_cache,
                mcp_client=self._resolve_mcp_client(runtime.mcp_profile),
            )
            if not runtime.instance.supports(context):
                continue
            result = runtime.instance.run(context)
            findings.extend(result)
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        recommendation = AgentRecommendation(
            taskId=event.task_id,
            stageId=event.stage_id,
            stageType=event.stage_type,
            findings=findings,
            elapsedMs=elapsed_ms,
            metadata={"agentCount": len(self.agents)},
        )
        self.callback_sink.send(recommendation)
        return recommendation

    def close(self) -> None:
        for client in self.mcp_clients.values():
            client.close()

    def __enter__(self) -> "AgentOrchestrator":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()
