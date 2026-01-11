"""Register built-in MCP HTTP adapters inside the AI service."""

from typing import Optional, Sequence

from fastapi import FastAPI

from . import file_reader, knowledge, sarif_rules


def register_builtin_routes(app: FastAPI, *, dependencies: Optional[Sequence[object]] = None) -> None:
    """Mount all built-in MCP routers under /builtin/* prefixes."""
    deps = list(dependencies) if dependencies else None
    app.include_router(file_reader.router, dependencies=deps)
    app.include_router(knowledge.router, dependencies=deps)
    app.include_router(sarif_rules.router, dependencies=deps)
