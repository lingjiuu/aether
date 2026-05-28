from __future__ import annotations

import importlib.util
from pathlib import Path

_ADAPTER_PATH = Path(__file__).resolve().parents[1] / "terminal-bench" / "aether_agent.py"
_SPEC = importlib.util.spec_from_file_location("evals_terminal_bench_aether_agent", _ADAPTER_PATH)
if _SPEC is None or _SPEC.loader is None:
    raise ImportError(f"Unable to load Aether Terminal-Bench adapter from {_ADAPTER_PATH}")

_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)

AetherAgent = _MODULE.AetherAgent
