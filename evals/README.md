# Aether Evals

This directory contains evaluation harness code that is intentionally separate from
the terminal frontend.

## Java eval runner

`src/main/java/io/github/lingjiuu/eval/EvalRunner.java` is the preferred
headless runner for evaluations. It runs Aether in-process through `UiRuntime`,
sets sandbox-friendly permissions, submits one instruction, waits for the
session to become idle, writes a JSON summary, and copies the isolated
`~/.aether` directory so logs, traces, transcripts, and tool artifacts are
collected by the harness.

Create the local eval config before building:

```sh
cp evals/aether-eval.example.toml evals/aether-eval.toml
```

`evals/aether-eval.toml` is gitignored because it may contain a real API key.
The committed example has the same shape:

```toml
# Tips: Only for OpenAI Responses API.
base_url = "https://api.openai.com/v1"
model = "gpt-5.5"
thinking_level = "xhigh"
auto_compact_token_limit = 115200
api_key = "sk-xxxxxx"
```

Build the container-friendly bundle:

```sh
evals/build-aether-eval-bundle.sh
```

Or build and run Terminal-Bench in one command:

```sh
evals/run-terminal-bench.sh --n-tasks 1 --n-concurrent 1
```

The script installs `harbor` with `uv` if needed, starts OrbStack/Docker when
Docker is not ready, validates `evals/aether-eval.toml`, builds the bundle, and
runs Harbor. `--n-tasks 1` is optional; it limits the run to one task for a
quick smoke test. Omit it to run the selected dataset/tasks without that limit.
Aether derives its internal runner timeout from each Terminal-Bench task timeout
while Harbor gets a small outer timeout multiplier for cleanup. The agent keeps
the task's full working budget; the extra outer time is for summary and trace
collection after Aether stops work.

The output is ignored by git and lives at:

```text
evals/.bundle/aether-eval
```

It contains compiled Maven classes/resources, runtime dependency jars, the copied
`evals/aether-eval.toml`, a shell wrapper, and downloaded Linux Temurin JRE 21
runtimes. For faster local checks, skip JRE downloads:

```sh
evals/build-aether-eval-bundle.sh --jre-arch none
```

Example:

```sh
evals/.bundle/aether-eval/bin/aether-eval \
  --instruction "Create hello.txt containing hello" \
  --session-cwd "$PWD" \
  --timeout-seconds 120
```

Useful environment variables:

- `AETHER_SESSION_CWD`: task workspace.
- `AETHER_EVAL_HOME`: isolated Aether home for config, logs, traces, and transcripts.
- `AETHER_EVAL_ARTIFACT_DIR`: directory for eval summary and copied Aether state.
- `AETHER_EVAL_TIMEOUT_SECONDS`: max runtime for one instruction.
- `AETHER_EVAL_TIMEOUT_GRACE_SECONDS`: seconds reserved for graceful log collection.
- `AETHER_TB_AGENT_TIMEOUT_MULTIPLIER`: Harbor agent timeout multiplier for cleanup room. Defaults to `1.05`.
- `AETHER_EVAL_CONFIG`: optional override path for the small eval config.
- `OPENAI_API_KEY`: needed only when `api_key = "$OPENAI_API_KEY"`.

Terminal-Bench evals require this bundle. There is no Node/Maven fallback inside
task containers.

## Output directory

Keep host-side benchmark output under:

```text
evals/results/terminal-bench
```

That directory is gitignored. Each run should put its task/trial artifacts there,
including the collected `aether-eval-summary.json` and `aether-home/` trace
bundle from `/logs/artifacts` inside the task container.
