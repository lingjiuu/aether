# Aether Evals

This directory contains evaluation harness code that is intentionally separate from
the terminal frontend.

## Shared runner

`evals/runner/runAetherTask.mjs` is a small Node.js headless runner. It starts the
Aether backend over stdio JSON-RPC, sets sandbox-friendly permissions, submits one
instruction, waits for the session to become idle, and writes a JSON summary.

Default backend command:

```sh
mvn -q compile exec:java -Dexec.mainClass=io.github.lingjiuu.App -Dexec.args=--stdio
```

For a future GraalVM binary, set:

```sh
AETHER_BIN=/path/to/aether node evals/runner/runAetherTask.mjs --instruction "..."
```

Useful environment variables:

- `AETHER_BIN`: native Aether binary to run with `--stdio`.
- `AETHER_BACKEND_COMMAND` / `AETHER_BACKEND_ARGS`: custom backend command.
- `AETHER_BACKEND_CWD`: backend working directory.
- `AETHER_SESSION_CWD`: task workspace.
- `AETHER_EVAL_HOME`: isolated Aether home for config, logs, traces, and transcripts.
- `AETHER_EVAL_ARTIFACT_DIR`: directory for eval summary and copied Aether state.
- `AETHER_EVAL_TIMEOUT_SECONDS`: max runtime for one instruction.
- `AETHER_CONFIG_TOML`: full Aether config content to write into the isolated home.
- `OPENAI_API_KEY`: used to generate a minimal OpenAI-compatible config when
  `AETHER_CONFIG_TOML` is not set.
- `AETHER_EVAL_MODEL_ID`: model for the generated config, defaulting to `gpt-5.5`.
- `AETHER_EVAL_DEFAULT_THINKING_LEVEL`: optional generated config reasoning effort.

Example:

```sh
node evals/runner/runAetherTask.mjs \
  --instruction "Create hello.txt containing hello" \
  --session-cwd "$PWD" \
  --timeout-seconds 120
```
