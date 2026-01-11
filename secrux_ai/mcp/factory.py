from __future__ import annotations

from typing import Dict

from ..config import MCPProfileConfig
from ..utils import load_from_entrypoint
from .base import BaseMCPClient
from .http import HttpMCPClient


def build_mcp_client(profile: MCPProfileConfig) -> BaseMCPClient:
    if profile.entrypoint:
        client_cls = load_from_entrypoint(profile.entrypoint)
        return client_cls(**profile.params)

    if profile.type == "http":
        params: Dict[str, object] = profile.params.copy()
        base_url = params.pop("base_url", None) or params.pop("baseUrl", None)
        api_key = params.pop("api_key", None) or params.pop("apiKey", None)
        timeout = params.pop("timeout_seconds", None) or params.pop("timeoutSeconds", None) or 30
        if not base_url:
            raise ValueError(f"MCP profile '{profile.name}' missing base_url in params.")
        return HttpMCPClient(base_url=base_url, api_key=api_key, timeout_seconds=int(timeout))

    raise ValueError(f"Unsupported MCP profile type '{profile.type}' without entrypoint.")

