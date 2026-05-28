# Terminal-Bench Adapter

This directory contains the Harbor adapter for running Aether on
Terminal-Bench 2.1.

The real adapter lives here:

```text
evals/terminal-bench/aether_agent.py
```

Because Python module names cannot contain `-`, Harbor should import the wrapper
package:

```sh
harbor run \
  -d terminal-bench/terminal-bench-2-1 \
  --agent-import-path evals.terminal_bench.aether_agent:AetherAgent \
  --n-concurrent 1
```

The adapter calls the shared runner:

```text
evals/runner/runAetherTask.mjs
```

Inside a Harbor task container, the default runner path is:

```text
/opt/aether/evals/runner/runAetherTask.mjs
```

For local experimentation, either provide a cloned repo at `/opt/aether`, set
`AETHER_REPO_URL` for the install script, or set `AETHER_BIN` to a prebuilt
Aether binary. The future GraalVM distribution should only need:

```sh
AETHER_BIN=/usr/local/bin/aether
```

The runner creates an isolated Aether home inside the task container and writes
`$AETHER_EVAL_HOME/.aether/config.toml` before launching Aether. Use either a
full config:

```sh
harbor run ... \
  --ae AETHER_CONFIG_TOML="$(cat ~/.aether/config.toml)"
```

or let the runner generate a minimal OpenAI-compatible config:

```sh
export AETHER_EVAL_MODEL_ID=gpt-5.5
export AETHER_EVAL_DEFAULT_THINKING_LEVEL=medium

harbor run ... \
  --ae OPENAI_API_KEY="$OPENAI_API_KEY"
```

For OpenAI-compatible gateways, also set:

```sh
export AETHER_EVAL_PROVIDER_ID=your-provider
export AETHER_EVAL_MODEL_BASE_URL=https://your-gateway.example/v1
```

The runner writes `/logs/artifacts/aether-eval-summary.json` and copies
`~/.aether` into `/logs/artifacts/aether-home` so traces, transcripts, and logs
can be collected by Harbor after each trial.
