from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, Iterable, List, Optional

from ..models import AgentContext, AgentFinding, StageType


class BaseAgent(ABC):
    """Base class for all agents."""

    def __init__(
        self,
        name: str,
        params: Optional[Dict[str, Any]] = None,
        stage_types: Optional[Iterable[str]] = None,
    ) -> None:
        self.name = name
        self.params = params or {}
        self.stage_types = {StageType(st) for st in stage_types} if stage_types else None

    def supports(self, context: AgentContext) -> bool:
        if self.stage_types is None:
            return True
        return context.event.stage_type in self.stage_types

    @abstractmethod
    def run(self, context: AgentContext) -> List[AgentFinding]:
        ...

