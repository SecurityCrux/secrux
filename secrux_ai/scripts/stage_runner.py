from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List

from ..config import PlatformConfig, load_platform_config
from ..models import ConfigProviderSpec, StageEvent
from ..orchestrator import AgentOrchestrator


def parse_key_values(values: List[str] | None) -> Dict[str, str]:
    result: Dict[str, str] = {}
    if not values:
        return result
    for item in values:
        if "=" not in item:
            raise ValueError(f"Invalid key=value pair '{item}'")
        key, value = item.split("=", 1)
        result[key] = value
    return result


def load_event(path: str) -> StageEvent:
    with Path(path).open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return StageEvent(**payload)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run configured AI agents for a stage event.")
    parser.add_argument("--event", required=True, help="Path to StageCompleted event JSON")
    parser.add_argument("--config", help="Path to configuration file")
    parser.add_argument("--config-provider", help="Config provider kind")
    parser.add_argument("--config-provider-entrypoint", help="Custom provider entrypoint (module:Class)")
    parser.add_argument(
        "--config-provider-param",
        action="append",
        help="Custom provider parameter key=value (can repeat)",
    )
    args = parser.parse_args()

    provider_spec = None
    if args.config_provider or args.config_provider_entrypoint:
        provider_spec = ConfigProviderSpec(
            kind=args.config_provider or "custom",
            entrypoint=args.config_provider_entrypoint,
            params=parse_key_values(args.config_provider_param),
        )

    config: PlatformConfig = load_platform_config(path=args.config, provider_spec=provider_spec)
    event = load_event(args.event)

    with AgentOrchestrator(config) as orchestrator:
        orchestrator.process(event)


if __name__ == "__main__":
    main()

