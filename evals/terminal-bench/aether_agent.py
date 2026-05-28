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
        repo_url = self._env_value("AETHER_REPO_URL")
        if repo_url:
            executor = self.exec_as_root if hasattr(self, "exec_as_root") else self.exec_as_agent
            await executor(
                environment,
                command=self._clone_repo_command(repo_url),
                timeout_sec=600,
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
        runner = self._env_value("AETHER_EVAL_RUNNER", "/opt/aether/evals/runner/runAetherTask.mjs")
        instruction_file = "/tmp/aether-eval/instruction.txt"
        timeout = self._env_value("AETHER_EVAL_TIMEOUT_SECONDS", "900")
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
            "AETHER_EVAL_PERMISSION_MODE": self._env_value("AETHER_EVAL_PERMISSION_MODE", "FULL_ACCESS"),
            "AETHER_EVAL_HOME": self._env_value("AETHER_EVAL_HOME", "/tmp/aether-home"),
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
            value = self._env_value(name)
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

    def _install_dependencies_command(self) -> str:
        return r"""
set -eu
if command -v apt-get >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    gzip \
    maven \
    nodejs \
    tar
  DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk || true
fi

java_major="$(java -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ {gsub(/ /, "", $2); print $2; exit}' || true)"
if [ -z "${java_major}" ] || [ "${java_major}" -lt 21 ]; then
  arch="$(uname -m)"
  case "${arch}" in
    x86_64|amd64) adoptium_arch="x64" ;;
    aarch64|arm64) adoptium_arch="aarch64" ;;
    *) echo "Unsupported architecture for JDK 21: ${arch}" >&2; exit 1 ;;
  esac
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${adoptium_arch}/jdk/hotspot/normal/eclipse" -o /tmp/jdk21.tar.gz
  rm -rf /opt/jdk-21
  mkdir -p /opt/jdk-21
  tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk-21 --strip-components=1
  ln -sf /opt/jdk-21/bin/java /usr/local/bin/java
  ln -sf /opt/jdk-21/bin/javac /usr/local/bin/javac
  ln -sf /opt/jdk-21/bin/jar /usr/local/bin/jar
fi

command -v node >/dev/null
command -v mvn >/dev/null
java -version
""".strip()

    def _clone_repo_command(self, repo_url: str) -> str:
        return (
            "rm -rf /opt/aether && "
            f"git clone --depth 1 {shlex.quote(repo_url)} /opt/aether && "
            "chmod -R a+rwX /opt/aether"
        )

    def _env_value(self, name: str, default: str | None = None) -> str | None:
        if hasattr(self, "_get_env"):
            value = self._get_env(name)
        else:
            value = os.environ.get(name)
        return value if value else default
