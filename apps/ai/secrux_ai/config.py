from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol

import yaml
from pydantic import BaseModel, Field

from .models import ConfigProviderSpec
from .utils import load_from_entrypoint


class MCPProfileConfig(BaseModel):
    name: str
    type: str = "http"
    entrypoint: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict)


class AgentConfig(BaseModel):
    name: str
    kind: str = "builtin"
    entrypoint: Optional[str] = None
    enabled: bool = True
    params: Dict[str, Any] = Field(default_factory=dict)
    stage_types: Optional[List[str]] = Field(None, alias="stageTypes")
    mcp_profile: Optional[str] = Field(None, alias="mcpProfile")


class CallbackConfig(BaseModel):
    mode: str = "stdout"  # stdout | webhook | file
    endpoint: Optional[str] = None
    headers: Dict[str, str] = Field(default_factory=dict)
    token: Optional[str] = None
    file_path: Optional[str] = Field(None, alias="filePath")


class PlatformConfig(BaseModel):
    agents: List[AgentConfig] = Field(default_factory=list)
    mcp_profiles: List[MCPProfileConfig] = Field(default_factory=list, alias="mcpProfiles")
    callbacks: CallbackConfig = Field(default_factory=CallbackConfig)
    provider: Optional[ConfigProviderSpec] = None


class ConfigProvider(Protocol):
    def load(self) -> Dict[str, Any]:
        ...


class FileConfigProvider:
    def __init__(self, path: Optional[str] = None):
        env_path = os.environ.get("SECRUX_AI_CONFIG")
        self.path = Path(path or env_path or "config.yaml")

    def load(self) -> Dict[str, Any]:
        if not self.path.exists():
            return {}
        with self.path.open("r", encoding="utf-8") as handle:
            return yaml.safe_load(handle) or {}


def build_provider(spec: Optional[ConfigProviderSpec], override_path: Optional[str] = None) -> ConfigProvider:
    if spec is None:
        return FileConfigProvider(path=override_path)

    if spec.entrypoint:
        provider_cls = load_from_entrypoint(spec.entrypoint)
        return provider_cls(**spec.params)

    if spec.kind == "file":
        return FileConfigProvider(path=override_path or spec.params.get("path"))

    raise ValueError(f"Unsupported config provider kind '{spec.kind}' without entrypoint.")


def load_platform_config(path: Optional[str] = None, provider_spec: Optional[ConfigProviderSpec] = None) -> PlatformConfig:
    provider = build_provider(provider_spec, override_path=path)
    raw = provider.load()
    return PlatformConfig(**raw)

