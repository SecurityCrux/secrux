from __future__ import annotations

import importlib
from typing import Any


class ImportErrorWithHint(ImportError):
    """Custom error that adds more context for dynamic imports."""


def load_from_entrypoint(entrypoint: str) -> Any:
    """Load a class or factory from an entrypoint string `module:attr`."""

    if ":" not in entrypoint:
        raise ImportErrorWithHint(f"Invalid entrypoint '{entrypoint}'. Use 'module:attr' syntax.")
    module_name, attr = entrypoint.split(":", 1)
    module = importlib.import_module(module_name)
    if not hasattr(module, attr):
        raise ImportErrorWithHint(f"Module '{module_name}' has no attribute '{attr}'.")
    return getattr(module, attr)

