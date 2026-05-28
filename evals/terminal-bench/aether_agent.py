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
        return os.environ.get("AETHER_EVAL_VERSION")

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
                command=(
                    "apt-get update && "
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y "
                    "ca-certificates curl git nodejs npm openjdk-21-jdk maven"
                ),
            )
        repo_url = os.environ.get("AETHER_REPO_URL")
        if repo_url and hasattr(self, "exec_as_agent"):
            await self.exec_as_agent(
                environment,
                command=(
                    "rm -rf /opt/aether && "
                    f"git clone --depth 1 {shlex.quote(repo_url)} /opt/aether"
                ),
            )

    @with_prompt_template
    async def run(self, instruction: str, environment, context) -> None:
        if not hasattr(self, "exec_as_agent"):
            raise RuntimeError("This Harbor version does not expose exec_as_agent.")
        await self.exec_as_agent(
            environment,
            command=self._env_prefix() + self._run_command(instruction),
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
        runner = os.environ.get("AETHER_EVAL_RUNNER", "/opt/aether/evals/runner/runAetherTask.mjs")
        instruction_file = "/tmp/aether-eval/instruction.txt"
        timeout = os.environ.get("AETHER_EVAL_TIMEOUT_SECONDS", "900")
        quoted_instruction = shlex.quote(instruction)
        return " && ".join([
            "mkdir -p /tmp/aether-eval /logs/artifacts",
            f"printf '%s' {quoted_instruction} > {instruction_file}",
            "node "
            + shlex.quote(runner)
            + " --instruction-file "
            + shlex.quote(instruction_file)
            + " --artifact-dir /logs/artifacts"
            + " --timeout-seconds "
            + shlex.quote(timeout),
        ])

    def _run_env(self) -> dict[str, str]:
        env = {
            "AETHER_EVAL_ARTIFACT_DIR": "/logs/artifacts",
            "AETHER_EVAL_PERMISSION_MODE": os.environ.get("AETHER_EVAL_PERMISSION_MODE", "FULL_ACCESS"),
            "AETHER_EVAL_HOME": os.environ.get("AETHER_EVAL_HOME", "/tmp/aether-home"),
        }
        for name in (
            "AETHER_BIN",
            "AETHER_BIN_ARGS",
            "AETHER_BACKEND_COMMAND",
            "AETHER_BACKEND_ARGS",
            "AETHER_BACKEND_CWD",
            "AETHER_SESSION_CWD",
            "AETHER_CONFIG_TOML",
            "AETHER_EVAL_PROVIDER_ID",
            "AETHER_EVAL_MODEL_ID",
            "AETHER_EVAL_MODEL_NAME",
            "AETHER_EVAL_MODEL_API",
            "AETHER_EVAL_MODEL_BASE_URL",
            "AETHER_EVAL_DEFAULT_THINKING_LEVEL",
            "AETHER_EVAL_CONTEXT_WINDOW",
            "AETHER_EVAL_AUTO_COMPACT_TOKEN_LIMIT",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "JAVA_TOOL_OPTIONS",
        ):
            value = os.environ.get(name)
            if value:
                env[name] = value
        return env

    def _env_prefix(self) -> str:
        sensitive_env_names = {"AETHER_CONFIG_TOML", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"}
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
