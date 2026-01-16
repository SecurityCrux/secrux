from __future__ import annotations

from typing import Any, Dict, Optional

import httpx

from .base import BaseMCPClient


class HttpMCPClient(BaseMCPClient):
    """HTTP-based MCP client."""

    def __init__(self, base_url: str, api_key: Optional[str] = None, timeout_seconds: int = 30):
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else None
        self._client = httpx.Client(base_url=base_url.rstrip("/"), timeout=timeout_seconds, headers=headers)

    def invoke_tool(self, tool: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        response = self._client.post(f"/tools/{tool}:invoke", json=payload)
        response.raise_for_status()
        return response.json()

    def fetch_context(self, resource: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        response = self._client.get(f"/context/{resource}", params=params)
        response.raise_for_status()
        return response.json()

    def health(self) -> bool:
        try:
            response = self._client.get("/healthz")
            response.raise_for_status()
            body = response.json()
            return body.get("status") == "ok"
        except httpx.HTTPError:
            return False

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "HttpMCPClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

