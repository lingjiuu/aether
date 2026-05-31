#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dataset="${AETHER_TB_DATASET:-terminal-bench/terminal-bench-2-1}"
jobs_dir="${AETHER_TB_RESULTS_DIR:-${repo_root}/evals/results/terminal-bench}"
bundle_dir="${AETHER_EVAL_BUNDLE_DIR:-${repo_root}/evals/.bundle/aether-eval}"
eval_config="${AETHER_EVAL_CONFIG_SOURCE:-${repo_root}/evals/aether-eval.toml}"
prompt_template_path="${AETHER_TB_PROMPT_TEMPLATE:-${repo_root}/evals/terminal-bench/prompt-template.md}"
jre_arch="${AETHER_EVAL_JRE_ARCH:-all}"
n_concurrent="${AETHER_TB_N_CONCURRENT:-1}"
timeout_grace_seconds="${AETHER_EVAL_TIMEOUT_GRACE_SECONDS:-20}"
agent_timeout_multiplier="${AETHER_TB_AGENT_TIMEOUT_MULTIPLIER:-1.05}"
n_tasks=""
job_name=""
skip_build=0
dry_run=0
harbor_args=()

usage() {
  cat <<'EOF'
Usage:
  evals/terminal-bench/run.sh [options] [-- extra harbor args...]

Options:
  --dataset NAME           Harbor dataset. Defaults to terminal-bench/terminal-bench-2-1.
  --task NAME              Run one task. Can be repeated.
  --include PATTERN        Include task glob. Can be repeated.
  --exclude PATTERN        Exclude task glob. Can be repeated.
  --n-tasks N              Limit number of tasks.
  --n-concurrent N         Concurrent trials. Defaults to 1.
  --jobs-dir PATH          Host output root. Defaults to evals/results/terminal-bench.
  --job-name NAME          Harbor job name.
  --jre-arch VALUE         all, current, linux-x64, linux-arm64, or none. Defaults to all.
  --skip-build             Reuse an existing eval bundle.
  --dry-run                Print the Harbor command without running it.
  -h, --help               Show this help.

Env:
  AETHER_EVAL_TIMEOUT_GRACE_SECONDS
                           Seconds reserved before each task timeout for graceful log collection. Defaults to 20.
  AETHER_TB_AGENT_TIMEOUT_MULTIPLIER
                           Harbor agent timeout multiplier used only to give cleanup room. Defaults to 1.05.

Before first use:
  cp evals/aether-eval.example.toml evals/aether-eval.toml
  edit evals/aether-eval.toml

If api_key = "$OPENAI_API_KEY", export OPENAI_API_KEY before running.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dataset)
      dataset="$2"
      shift 2
      ;;
    --dataset=*)
      dataset="${1#*=}"
      shift
      ;;
    --task)
      harbor_args+=(--task "$2")
      shift 2
      ;;
    --task=*)
      harbor_args+=(--task "${1#*=}")
      shift
      ;;
    --include)
      harbor_args+=(--include-task-name "$2")
      shift 2
      ;;
    --include=*)
      harbor_args+=(--include-task-name "${1#*=}")
      shift
      ;;
    --exclude)
      harbor_args+=(--exclude-task-name "$2")
      shift 2
      ;;
    --exclude=*)
      harbor_args+=(--exclude-task-name "${1#*=}")
      shift
      ;;
    --n-tasks)
      n_tasks="$2"
      shift 2
      ;;
    --n-tasks=*)
      n_tasks="${1#*=}"
      shift
      ;;
    --n-concurrent)
      n_concurrent="$2"
      shift 2
      ;;
    --n-concurrent=*)
      n_concurrent="${1#*=}"
      shift
      ;;
    --jobs-dir)
      jobs_dir="$2"
      shift 2
      ;;
    --jobs-dir=*)
      jobs_dir="${1#*=}"
      shift
      ;;
    --job-name)
      job_name="$2"
      shift 2
      ;;
    --job-name=*)
      job_name="${1#*=}"
      shift
      ;;
    --jre-arch)
      jre_arch="$2"
      shift 2
      ;;
    --jre-arch=*)
      jre_arch="${1#*=}"
      shift
      ;;
    --skip-build)
      skip_build=1
      shift
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      harbor_args+=("$@")
      break
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

ensure_python() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required." >&2
    exit 1
  fi
}

ensure_harbor() {
  if command -v harbor >/dev/null 2>&1; then
    return
  fi
  if ! command -v uv >/dev/null 2>&1; then
    echo "harbor is not installed, and uv was not found to install it." >&2
    echo "Install uv first, then rerun this script." >&2
    exit 1
  fi
  echo "harbor is not installed; installing with uv..."
  uv tool install harbor
  export PATH="${HOME}/.local/bin:${PATH}"
  if ! command -v harbor >/dev/null 2>&1; then
    echo "uv installed harbor, but harbor is still not on PATH." >&2
    echo "Try adding ${HOME}/.local/bin to PATH." >&2
    exit 1
  fi
}

wait_for_docker() {
  attempts="${1:-60}"
  for _ in $(seq 1 "$attempts"); do
    if docker info >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

ensure_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required. Install Docker Desktop or OrbStack first." >&2
    exit 1
  fi
  if docker info >/dev/null 2>&1; then
    return
  fi
  if command -v orbctl >/dev/null 2>&1; then
    echo "Docker is not ready; starting OrbStack..."
    orbctl start
    if wait_for_docker 60; then
      return
    fi
  elif [ "$(uname -s)" = "Darwin" ] && command -v open >/dev/null 2>&1; then
    echo "Docker is not ready; trying to start OrbStack or Docker Desktop..."
    open -a OrbStack >/dev/null 2>&1 || open -a Docker >/dev/null 2>&1 || true
    if wait_for_docker 60; then
      return
    fi
  fi
  echo "Docker is still not ready. Start OrbStack/Docker and rerun this script." >&2
  exit 1
}

validate_eval_config() {
  python3 - "$eval_config" <<'PY'
import os
import sys
try:
    import tomllib
except ModuleNotFoundError:
    print("python3 with tomllib is required; use Python 3.11+.", file=sys.stderr)
    sys.exit(1)

path = sys.argv[1]
try:
    with open(path, "rb") as fh:
        config = tomllib.load(fh)
except FileNotFoundError:
    print(f"Eval config does not exist: {path}", file=sys.stderr)
    print("Copy evals/aether-eval.example.toml to evals/aether-eval.toml and fill in the eval settings.", file=sys.stderr)
    sys.exit(1)
except tomllib.TOMLDecodeError as exc:
    print(f"Failed to parse eval config {path}: {exc}", file=sys.stderr)
    sys.exit(1)

missing = [
    key for key in ("base_url", "model", "thinking_level", "auto_compact_token_limit", "api_key")
    if key not in config or config[key] in (None, "")
]
if missing:
    print(f"Eval config {path} is missing required field(s): {', '.join(missing)}", file=sys.stderr)
    sys.exit(1)

if not isinstance(config["base_url"], str) or not config["base_url"].strip():
    print("base_url must be a non-empty string.", file=sys.stderr)
    sys.exit(1)
if not isinstance(config["model"], str) or not config["model"].strip():
    print("model must be a non-empty string.", file=sys.stderr)
    sys.exit(1)
if not isinstance(config["thinking_level"], str) or not config["thinking_level"].strip():
    print("thinking_level must be a non-empty string.", file=sys.stderr)
    sys.exit(1)
if config["thinking_level"].strip().replace("-", "_").lower() not in {"xhigh", "high", "medium", "low", "minimal", "none"}:
    print("thinking_level must be one of xhigh, high, medium, low, minimal, none.", file=sys.stderr)
    sys.exit(1)
if not isinstance(config["api_key"], str) or not config["api_key"].strip():
    print("api_key must be a non-empty string.", file=sys.stderr)
    sys.exit(1)
if not isinstance(config["auto_compact_token_limit"], int) or config["auto_compact_token_limit"] <= 0:
    print("auto_compact_token_limit must be a positive integer.", file=sys.stderr)
    sys.exit(1)

api_key = config["api_key"].strip()
if api_key == "$OPENAI_API_KEY" and not os.environ.get("OPENAI_API_KEY"):
    print(f'OPENAI_API_KEY is required because {path} has api_key = "$OPENAI_API_KEY".', file=sys.stderr)
    sys.exit(1)
PY
}

validate_timeout_grace() {
  case "$timeout_grace_seconds" in
    ''|*[!0-9]*)
      echo "AETHER_EVAL_TIMEOUT_GRACE_SECONDS must be a non-negative integer." >&2
      exit 1
      ;;
  esac
}

validate_agent_timeout_multiplier() {
  python3 - "$agent_timeout_multiplier" <<'PY'
import sys

try:
    value = float(sys.argv[1])
except ValueError:
    print("AETHER_TB_AGENT_TIMEOUT_MULTIPLIER must be at least 1.0.", file=sys.stderr)
    sys.exit(1)

if value < 1.0:
    print("AETHER_TB_AGENT_TIMEOUT_MULTIPLIER must be at least 1.0.", file=sys.stderr)
    sys.exit(1)
PY
}

validate_timeout_grace
ensure_python
validate_agent_timeout_multiplier
ensure_harbor
ensure_docker

if [ ! -f "$eval_config" ]; then
  echo "Eval config does not exist: $eval_config" >&2
  echo "Copy evals/aether-eval.example.toml to evals/aether-eval.toml and fill in the eval settings." >&2
  exit 1
fi
validate_eval_config

if [ "$skip_build" -eq 0 ]; then
  "${repo_root}/evals/build-aether-eval-bundle.sh" \
    --bundle-dir "$bundle_dir" \
    --eval-config "$eval_config" \
    --jre-arch "$jre_arch"
fi

if [ ! -x "${bundle_dir}/bin/aether-eval" ]; then
  echo "Eval bundle runner not found: ${bundle_dir}/bin/aether-eval" >&2
  exit 1
fi
if [ ! -f "$prompt_template_path" ]; then
  echo "Prompt template does not exist: $prompt_template_path" >&2
  exit 1
fi

mkdir -p "$jobs_dir"
bundle_dir="$(cd "$bundle_dir" && pwd)"
jobs_dir="$(cd "$jobs_dir" && pwd)"
prompt_template_path="$(cd "$(dirname "$prompt_template_path")" && pwd)/$(basename "$prompt_template_path")"
mounts_json="$(
  python3 - "$bundle_dir" <<'PY'
import json
import sys
print(json.dumps([{
    "type": "bind",
    "source": sys.argv[1],
    "target": "/opt/aether-eval-bundle",
    "read_only": True,
    "bind": {"create_host_path": False},
}]))
PY
)"

cmd=(
  harbor run
  --dataset "$dataset"
  --agent-timeout-multiplier "$agent_timeout_multiplier"
  --agent-import-path "evals.terminal_bench.aether_agent:AetherAgent"
  --agent-kwarg "prompt_template_path=${prompt_template_path}"
  --agent-kwarg "eval_timeout_grace_seconds=${timeout_grace_seconds}"
  --jobs-dir "$jobs_dir"
  --mounts "$mounts_json"
  --agent-env "AETHER_EVAL_BUNDLE=/opt/aether-eval-bundle"
  --n-concurrent "$n_concurrent"
  --yes
)

if [ -n "$n_tasks" ]; then
  cmd+=(--n-tasks "$n_tasks")
fi
if [ -n "$job_name" ]; then
  cmd+=(--job-name "$job_name")
fi
if [ -n "${OPENAI_API_KEY:-}" ]; then
  cmd+=(--agent-env "OPENAI_API_KEY=${OPENAI_API_KEY}")
fi
cmd+=("${harbor_args[@]}")

printf 'Results directory: %s\n' "$jobs_dir"
printf 'Command:'
printf ' %q' "${cmd[@]}"
printf '\n'

if [ "$dry_run" -eq 1 ]; then
  exit 0
fi

(
  cd "$repo_root"
  "${cmd[@]}"
)
