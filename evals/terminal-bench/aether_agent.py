from __future__ import annotations

import json
import math
import os
import shlex
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - Harbor uses Python 3.11+.
    tomllib = None  # type: ignore[assignment]

try:
    from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
except ImportError:  # Allows local syntax checks without Harbor installed.
    class BaseInstalledAgent:  # type: ignore[no-redef]
        pass

    def with_prompt_template(func):  # type: ignore[no-redef]
        return func

try:
    from harbor.agents.installed.base import ExecInput
except ImportError:  # Harbor versions before/after the docs expose this differently.
    ExecInput = None  # type: ignore[assignment]


DEFAULT_EVAL_TIMEOUT_SECONDS = 900
DEFAULT_TIMEOUT_GRACE_SECONDS = 20


class AetherAgent(BaseInstalledAgent):
    """Harbor installed-agent adapter for Aether on Terminal-Bench."""

    def __init__(
        self,
        *args,
        eval_timeout_grace_seconds: int | float | str | None = None,
        **kwargs,
    ):
        super().__init__(*args, **kwargs)
        if eval_timeout_grace_seconds is None:
            eval_timeout_grace_seconds = self._env_value("AETHER_EVAL_TIMEOUT_GRACE_SECONDS")
        self._eval_timeout_grace_seconds = self._non_negative_seconds(
            eval_timeout_grace_seconds,
            "eval_timeout_grace_seconds",
            DEFAULT_TIMEOUT_GRACE_SECONDS,
        )

    @staticmethod
    def name() -> str:
        return "aether"

    def version(self) -> str | None:
        return self._env_value("AETHER_EVAL_VERSION")

    def render_instruction(self, instruction: str) -> str:
        template_path = getattr(self, "_prompt_template_path", None)
        if not template_path:
            return instruction

        try:
            from jinja2 import Environment, StrictUndefined
        except ImportError as exc:
            raise RuntimeError("Jinja2 is required to render the Aether prompt template.") from exc

        template_file = Path(template_path)
        if not template_file.exists():
            raise FileNotFoundError(f"Template file not found: {template_file}")

        template = Environment(undefined=StrictUndefined).from_string(template_file.read_text())
        return template.render(
            instruction=instruction,
            task_timeout_seconds=self._eval_timeout_seconds(),
        )

    @property
    def _install_agent_template_path(self) -> Path:
        return Path(__file__).with_name("install_aether.sh.j2")

    def create_run_agent_commands(self, instruction: str) -> list:
        if ExecInput is None:
            raise RuntimeError("This Harbor version does not expose ExecInput.")
        timeout = self._eval_timeout_seconds()
        return [
            ExecInput(
                command=self._run_command(instruction, timeout),
                env=self._run_env(),
                timeout_sec=self._command_timeout_seconds(timeout),
            )
        ]

    async def install(self, environment) -> None:
        if hasattr(self, "exec_as_root"):
            await self.exec_as_root(
                environment,
                command=self._install_dependencies_command(),
                timeout_sec=900,
            )

    @with_prompt_template
    async def run(self, instruction: str, environment, context) -> None:
        if not hasattr(self, "exec_as_agent"):
            raise RuntimeError("This Harbor version does not expose exec_as_agent.")
        timeout = self._eval_timeout_seconds()
        await self.exec_as_agent(
            environment,
            command=self._run_command(instruction, timeout),
            env=self._run_env(),
            timeout_sec=self._command_timeout_seconds(timeout),
        )

    def populate_context_post_run(self, context) -> None:
        summary_path = self._first_existing_artifact("aether-eval-summary.json")
        if not summary_path.exists():
            return
        try:
            summary = json.loads(summary_path.read_text())
        except Exception:
            return
        if hasattr(context, "agent_metadata"):
            context.agent_metadata = {
                **(context.agent_metadata or {}),
                "aether": summary,
            }
        if hasattr(context, "trajectory_path"):
            trajectory = self._first_existing_artifact("trajectory.json")
            if trajectory.exists():
                context.trajectory_path = trajectory

    def _run_command(self, instruction: str, timeout_seconds: int | None = None) -> str:
        bundle = self._env_value("AETHER_EVAL_BUNDLE", "/opt/aether-eval-bundle")
        instruction_file = "/tmp/aether-eval/instruction.txt"
        timeout = str(timeout_seconds or self._eval_timeout_seconds())
        quoted_instruction = shlex.quote(instruction)
        runner = shlex.quote(f"{bundle}/bin/aether-eval")
        return " && ".join([
            "mkdir -p /tmp/aether-eval /logs/artifacts",
            f"printf '%s' {quoted_instruction} > {instruction_file}",
            "test -x "
            + runner
            + " || { echo 'Aether eval bundle is required at "
            + shlex.quote(f"{bundle}/bin/aether-eval")
            + "' >&2; exit 1; }",
            runner
            + " --instruction-file "
            + shlex.quote(instruction_file)
            + " --artifact-dir /logs/artifacts"
            + " --timeout-seconds "
            + shlex.quote(timeout),
        ])

    def _run_env(self) -> dict[str, str]:
        env = {
            "AETHER_EVAL_BUNDLE": self._env_value("AETHER_EVAL_BUNDLE", "/opt/aether-eval-bundle"),
            "AETHER_EVAL_ARTIFACT_DIR": "/logs/artifacts",
            "AETHER_EVAL_PERMISSION_MODE": self._env_value("AETHER_EVAL_PERMISSION_MODE", "FULL_ACCESS"),
            "AETHER_EVAL_HOME": self._env_value("AETHER_EVAL_HOME", "/tmp/aether-home"),
        }
        for name in (
            "AETHER_SESSION_CWD",
            "AETHER_EVAL_CONFIG",
            "AETHER_EVAL_JAVA_OPTS",
            "OPENAI_API_KEY",
            "JAVA_TOOL_OPTIONS",
        ):
            value = self._env_value(name)
            if value:
                env[name] = value
        return env

    def _eval_timeout_seconds(self) -> int:
        explicit = self._env_value("AETHER_EVAL_TIMEOUT_SECONDS")
        if explicit:
            return self._positive_seconds(explicit, "AETHER_EVAL_TIMEOUT_SECONDS")

        timeouts = self._harbor_agent_timeouts()
        if timeouts is None:
            return DEFAULT_EVAL_TIMEOUT_SECONDS

        base_timeout, outer_timeout = timeouts
        if outer_timeout >= base_timeout + self._eval_timeout_grace_seconds:
            return max(1, int(math.floor(base_timeout)))

        reserve = self._timeout_reserve_seconds(outer_timeout)
        return max(1, int(math.floor(outer_timeout - reserve)))

    def _command_timeout_seconds(self, eval_timeout_seconds: int) -> int:
        timeouts = self._harbor_agent_timeouts()
        if timeouts is not None:
            _, outer_timeout = timeouts
            return max(1, int(math.ceil(outer_timeout)))
        return max(1, eval_timeout_seconds + self._eval_timeout_grace_seconds)

    def _timeout_reserve_seconds(self, harbor_timeout_seconds: float) -> int:
        if self._eval_timeout_grace_seconds <= 0:
            return 0
        ten_percent = max(1, int(math.floor(harbor_timeout_seconds * 0.1)))
        return min(self._eval_timeout_grace_seconds, ten_percent)

    def _harbor_agent_timeouts(self) -> tuple[float, float] | None:
        config = self._trial_config()
        if not config:
            return None

        agent_config = config.get("agent") or {}
        base_timeout = self._optional_number(agent_config.get("override_timeout_sec"))
        if base_timeout is None:
            task_path = (config.get("task") or {}).get("path")
            base_timeout = self._task_agent_timeout_seconds(task_path)
        if base_timeout is None:
            return None

        max_timeout = self._optional_number(agent_config.get("max_timeout_sec"))
        if max_timeout is not None:
            base_timeout = min(base_timeout, max_timeout)

        multiplier = self._optional_number(config.get("agent_timeout_multiplier"))
        if multiplier is None:
            multiplier = self._optional_number(config.get("timeout_multiplier"))
        outer_timeout = base_timeout * (multiplier if multiplier is not None else 1.0)
        return base_timeout, outer_timeout

    def _trial_config(self) -> dict | None:
        logs_dir = getattr(self, "logs_dir", None)
        if not logs_dir:
            return None
        logs_path = Path(logs_dir)
        for path in (logs_path, *logs_path.parents):
            candidate = path / "config.json"
            if not candidate.exists():
                continue
            try:
                data = json.loads(candidate.read_text())
            except Exception:
                return None
            return data if isinstance(data, dict) else None
        return None

    def _task_agent_timeout_seconds(self, task_path: str | None) -> float | None:
        if not task_path or tomllib is None:
            return None
        task_toml = Path(task_path) / "task.toml"
        try:
            with task_toml.open("rb") as handle:
                task_config = tomllib.load(handle)
        except Exception:
            return None
        return self._optional_number((task_config.get("agent") or {}).get("timeout_sec"))

    def _env_prefix(self) -> str:
        sensitive_env_names = {"OPENAI_API_KEY"}
        return "".join(
            f"export {name}={shlex.quote(value)}; "
            for name, value in self._run_env().items()
            if name not in sensitive_env_names
        )

    def _first_existing_artifact(self, name: str) -> Path:
        candidates = [Path("/logs/artifacts") / name]
        logs_dir = getattr(self, "logs_dir", None)
        if logs_dir:
            logs_path = Path(logs_dir)
            candidates.extend([
                logs_path / name,
                logs_path / "artifacts" / name,
            ])
        for candidate in candidates:
            if candidate.exists():
                return candidate
        return candidates[0]

    def _install_dependencies_command(self) -> str:
        bundle = self._env_value("AETHER_EVAL_BUNDLE", "/opt/aether-eval-bundle")
        return (
            "export AETHER_EVAL_BUNDLE="
            + shlex.quote(bundle)
            + ";\n"
            + r"""
set -eu
bundle="${AETHER_EVAL_BUNDLE:-/opt/aether-eval-bundle}"
if [ ! -x "${bundle}/bin/aether-eval" ]; then
  echo "Aether eval bundle is required at ${bundle}/bin/aether-eval." >&2
  echo "Build it with evals/build-aether-eval-bundle.sh and mount it into the task container." >&2
  exit 1
fi
"${bundle}/bin/aether-eval" --help >/dev/null
mkdir -p /logs/artifacts /tmp/aether-home
""".strip()
        )

    def _env_value(self, name: str, default: str | None = None) -> str | None:
        if hasattr(self, "_get_env"):
            value = self._get_env(name)
        else:
            value = os.environ.get(name)
        return value if value else default

    @staticmethod
    def _positive_seconds(value: int | float | str, name: str) -> int:
        try:
            seconds = int(math.floor(float(value)))
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{name} must be a positive number of seconds.") from exc
        if seconds <= 0:
            raise ValueError(f"{name} must be a positive number of seconds.")
        return seconds

    @classmethod
    def _non_negative_seconds(
        cls,
        value: int | float | str | None,
        name: str,
        default: int,
    ) -> int:
        if value is None or value == "":
            return default
        try:
            seconds = int(math.floor(float(value)))
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{name} must be a non-negative number of seconds.") from exc
        if seconds < 0:
            raise ValueError(f"{name} must be a non-negative number of seconds.")
        return seconds

    @staticmethod
    def _optional_number(value) -> float | None:
        if value is None or value == "":
            return None
        try:
            return float(value)
        except (TypeError, ValueError):
            return None
