# Terminal-Bench 2.1

## Setup

```sh
cp evals/aether-eval.example.toml evals/aether-eval.toml
```

Edit `evals/aether-eval.toml`:

```toml
# Tips: Only for OpenAI Responses API.
base_url = "https://api.openai.com/v1"
model = "gpt-5.5"
thinking_level = "xhigh"
auto_compact_token_limit = 115200
api_key = "sk-xxxxxx"
```

If `api_key = "$OPENAI_API_KEY"`, export the key:

```sh
export OPENAI_API_KEY=...
```

## Run

Smoke test one dataset task:

```sh
evals/run-terminal-bench.sh --n-tasks 1
```

Run a specific task:

```sh
evals/run-terminal-bench.sh --task <task-name>
```

Run tasks matching a pattern:

```sh
evals/run-terminal-bench.sh --include '<pattern>' --n-tasks 5
```

Run the full dataset:

```sh
evals/run-terminal-bench.sh
```

## Script Options

```text
--task NAME         Run one named task. Can be repeated.
--include PATTERN   Include task glob. Can be repeated.
--exclude PATTERN   Exclude task glob. Can be repeated.
--n-tasks N         Limit task count.
--n-concurrent N    Concurrent trials. Defaults to 1.
--jobs-dir PATH     Results root. Defaults to evals/results/terminal-bench.
--job-name NAME     Harbor job name.
--skip-build        Reuse the existing eval bundle.
--dry-run           Print the Harbor command only.
```

The script installs `harbor` with `uv` if missing, starts OrbStack/Docker if
Docker is not ready, validates `evals/aether-eval.toml`, builds the eval bundle,
and runs Harbor.

## Results

Host results:

```text
evals/results/terminal-bench
```

Collected Aether artifacts per trial:

```text
artifacts/aether-eval-summary.json
artifacts/aether-home/logs/
artifacts/aether-home/traces/
artifacts/aether-home/transcripts/
```
