from __future__ import annotations

from typing import Any, Dict, Optional, Protocol


class BaseMCPClient(Protocol):
    """Minimal MCP client interface used by agents."""

    def invoke_tool(self, tool: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        ...

    def fetch_context(self, resource: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        ...

    def health(self) -> bool:
        ...

    def close(self) -> None:
        ...

