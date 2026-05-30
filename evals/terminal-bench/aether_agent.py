from __future__ import annotations

import json
import os
import shlex
from pathlib import Path

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


class AetherAgent(BaseInstalledAgent):
    """Harbor installed-agent adapter for Aether on Terminal-Bench."""

    @staticmethod
    def name() -> str:
        return "aether"

    def version(self) -> str | None:
        return self._env_value("AETHER_EVAL_VERSION")

    @property
    def _install_agent_template_path(self) -> Path:
        return Path(__file__).with_name("install_aether.sh.j2")

    def create_run_agent_commands(self, instruction: str) -> list:
        if ExecInput is None:
            raise RuntimeError("This Harbor version does not expose ExecInput.")
        return [
            ExecInput(
                command=self._run_command(instruction),
                env=self._run_env(),
                timeout_sec=int(os.environ.get("AETHER_EVAL_TIMEOUT_SECONDS", "900")),
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
        await self.exec_as_agent(
            environment,
            command=self._run_command(instruction),
            env=self._run_env(),
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

    def _run_command(self, instruction: str) -> str:
        bundle = self._env_value("AETHER_EVAL_BUNDLE", "/opt/aether-eval-bundle")
        instruction_file = "/tmp/aether-eval/instruction.txt"
        timeout = self._env_value("AETHER_EVAL_TIMEOUT_SECONDS", "900")
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
