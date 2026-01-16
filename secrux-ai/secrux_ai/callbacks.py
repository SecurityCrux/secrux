from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, Optional, Protocol

import httpx
from rich.console import Console

from .models import AgentRecommendation


class CallbackSink(Protocol):
    def send(self, recommendation: AgentRecommendation) -> None:
        ...


class StdoutCallbackSink:
    def __init__(self) -> None:
        self.console = Console()

    def send(self, recommendation: AgentRecommendation) -> None:
        self.console.print_json(recommendation.model_dump_json(by_alias=True))


class WebhookCallbackSink:
    def __init__(self, endpoint: str, headers: Optional[Dict[str, str]] = None, token: Optional[str] = None):
        self.endpoint = endpoint
        self.headers = headers or {}
        if token:
            self.headers.setdefault("Authorization", f"Bearer {token}")
        self.client = httpx.Client()

    def send(self, recommendation: AgentRecommendation) -> None:
        payload = recommendation.model_dump(mode="json", by_alias=True)
        response = self.client.post(self.endpoint, json=payload, headers=self.headers)
        response.raise_for_status()


class FileCallbackSink:
    def __init__(self, path: str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def send(self, recommendation: AgentRecommendation) -> None:
        payload = recommendation.model_dump(mode="json", by_alias=True)
        with self.path.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
