#!/usr/bin/env node
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AetherEvalClient } from './AetherEvalClient.mjs';

const DEFAULT_TIMEOUT_SECONDS = 900;
const DEFAULT_POLL_MS = 1000;

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    printHelp();
    return;
  }

  const instruction = await readInstruction(args);
  const repoRoot = findRepoRoot(args.backendCwd);
  const sessionCwd = resolve(args.sessionCwd || process.env.AETHER_SESSION_CWD || process.cwd());
  const artifactDir = args.artifactDir
    || process.env.AETHER_EVAL_ARTIFACT_DIR
    || (existsSync('/logs/artifacts') ? '/logs/artifacts' : null);
  const timeoutSeconds = numberArg(args.timeoutSeconds, process.env.AETHER_EVAL_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
  const pollMs = numberArg(args.pollMs, process.env.AETHER_EVAL_POLL_MS, DEFAULT_POLL_MS);
  const permissionMode = args.permissionMode || process.env.AETHER_EVAL_PERMISSION_MODE || 'FULL_ACCESS';
  const home = args.home || process.env.AETHER_EVAL_HOME || process.env.HOME || homedir();
  const backend = backendCommand(args, repoRoot);
  const modelConfig = modelConfigSummary();

  if (args.dryRun) {
    console.log(JSON.stringify({
      backend,
      sessionCwd,
      artifactDir,
      timeoutSeconds,
      pollMs,
      permissionMode,
      home,
      modelConfig,
    }, null, 2));
    return;
  }

  await ensureAetherConfig(home);

  const stderrChunks = [];
  const events = [];
  let lastSequence = 0;
  let timedOut = false;
  let cancelled = false;
  let summary = null;
  const startedAt = Date.now();

  const client = new AetherEvalClient({
    command: backend.command,
    args: backend.args,
    cwd: backend.cwd,
    env: {
      HOME: home,
      JAVA_TOOL_OPTIONS: javaToolOptions(home),
      AETHER_SESSION_CWD: sessionCwd,
    },
  });

  client.onStderr(text => stderrChunks.push(text));
  client.onEvent(event => {
    events.push(event);
    if (event && typeof event.sequence === 'number') {
      lastSequence = Math.max(lastSequence, event.sequence);
    }
  });

  try {
    client.start();
    const initialized = await client.initialize();
    await client.initialized();
    const permissionAck = await client.setPermissionMode(permissionMode);
    ensureAccepted(permissionAck, 'permission/set');
    const submitAck = await client.submitText(instruction);
    ensureAccepted(submitAck, 'turn/submit');

    const deadline = Date.now() + timeoutSeconds * 1000;
    let session = await client.currentSession();
    while ((session.status || '').toUpperCase() === 'RUNNING') {
      if (Date.now() >= deadline) {
        timedOut = true;
        break;
      }
      await sleep(pollMs);
      const page = await client.eventsAfter(lastSequence, 500);
      for (const event of page.events || []) {
        events.push(event);
        if (event && typeof event.sequence === 'number') {
          lastSequence = Math.max(lastSequence, event.sequence);
        }
      }
      session = await client.currentSession();
    }

    if (timedOut) {
      cancelled = true;
      await client.cancelTurn().catch(() => null);
      await sleep(Math.min(pollMs, 1000));
      session = await client.currentSession().catch(() => session);
    }

    const history = await client.history().catch(() => null);
    summary = {
      ok: !timedOut,
      timedOut,
      cancelled,
      protocolVersion: initialized.protocolVersion || null,
      sessionId: session.sessionId || initialized.sessionId || null,
      status: session.status || null,
      messageCount: session.messageCount ?? null,
      permissionMode: session.permissionMode || permissionMode,
      durationMs: Date.now() - startedAt,
      eventCount: events.length,
      lastSequence,
      historyTurns: Array.isArray(history?.turns) ? history.turns.length : null,
      backend,
      modelConfig,
      sessionCwd,
      artifactDir,
      aetherHome: home,
      stderrPreview: stderrChunks.join('').slice(0, 8000),
    };

    await writeSummary(summary, args.summaryFile, artifactDir);
    await copyAetherArtifacts(home, artifactDir);
    console.log(JSON.stringify(summary));
    if (timedOut) {
      process.exitCode = 124;
    }
  } catch (error) {
    summary = {
      ok: false,
      error: error instanceof Error ? error.message : String(error),
      durationMs: Date.now() - startedAt,
      backend,
      modelConfig,
      sessionCwd,
      artifactDir,
      aetherHome: home,
      stderrPreview: stderrChunks.join('').slice(0, 8000),
    };
    await writeSummary(summary, args.summaryFile, artifactDir).catch(() => null);
    console.error(JSON.stringify(summary));
    process.exitCode = 1;
  } finally {
    client.close();
  }
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      args.help = true;
    } else if (arg === '--dry-run') {
      args.dryRun = true;
    } else if (arg.startsWith('--') && arg.includes('=')) {
      const [name, ...valueParts] = arg.slice(2).split('=');
      args[toCamel(name)] = valueParts.join('=');
    } else if (arg.startsWith('--')) {
      const name = toCamel(arg.slice(2));
      const next = argv[i + 1];
      if (next == null || next.startsWith('--')) {
        args[name] = true;
      } else {
        args[name] = next;
        i++;
      }
    } else {
      args._ = [...(args._ || []), arg];
    }
  }
  return args;
}

async function readInstruction(args) {
  if (args.instructionFile) {
    return (await readFile(args.instructionFile, 'utf8')).trim();
  }
  if (args.instruction) {
    return String(args.instruction).trim();
  }
  if (args._?.length) {
    return args._.join(' ').trim();
  }
  throw new Error('instruction is required; pass --instruction or --instruction-file');
}

function backendCommand(args, repoRoot) {
  if (args.backendCommand) {
    return {
      command: args.backendCommand,
      args: splitArgs(args.backendArgs || ''),
      cwd: args.backendCwd || repoRoot,
    };
  }
  if (process.env.AETHER_BACKEND_COMMAND) {
    return {
      command: process.env.AETHER_BACKEND_COMMAND,
      args: splitArgs(process.env.AETHER_BACKEND_ARGS || ''),
      cwd: args.backendCwd || process.env.AETHER_BACKEND_CWD || repoRoot,
    };
  }
  if (process.env.AETHER_BIN) {
    return {
      command: process.env.AETHER_BIN,
      args: splitArgs(process.env.AETHER_BIN_ARGS || '--stdio'),
      cwd: args.backendCwd || repoRoot,
    };
  }
  return {
    command: 'mvn',
    args: ['-q', 'exec:java', '-Dexec.mainClass=io.github.lingjiuu.App', '-Dexec.args=--stdio'],
    cwd: args.backendCwd || repoRoot,
  };
}

function findRepoRoot(explicit) {
  if (explicit) {
    return resolve(explicit);
  }
  const here = dirname(fileURLToPath(import.meta.url));
  return resolve(here, '../..');
}

async function writeSummary(summary, summaryFile, artifactDir) {
  const body = JSON.stringify(summary, null, 2) + '\n';
  if (summaryFile) {
    await mkdir(dirname(resolve(summaryFile)), { recursive: true });
    await writeFile(summaryFile, body);
  }
  if (artifactDir) {
    await mkdir(artifactDir, { recursive: true });
    await writeFile(resolve(artifactDir, 'aether-eval-summary.json'), body);
  }
}

async function copyAetherArtifacts(home, artifactDir) {
  if (!artifactDir) {
    return;
  }
  const aetherDir = resolve(home, '.aether');
  if (!existsSync(aetherDir)) {
    return;
  }
  await mkdir(artifactDir, { recursive: true });
  await cp(aetherDir, resolve(artifactDir, 'aether-home'), {
    recursive: true,
    force: true,
    errorOnExist: false,
  });
}

async function ensureAetherConfig(home) {
  const configPath = resolve(home, '.aether', 'config.toml');
  if (existsSync(configPath)) {
    return;
  }
  const body = aetherConfigToml();
  if (!body) {
    throw new Error(
      'Aether config is missing. Set AETHER_CONFIG_TOML, or set OPENAI_API_KEY and optionally AETHER_EVAL_MODEL_ID.'
    );
  }
  await mkdir(dirname(configPath), { recursive: true });
  await writeFile(configPath, body);
}

function aetherConfigToml() {
  const explicit = process.env.AETHER_CONFIG_TOML;
  if (explicit && explicit.trim()) {
    return explicit.trim() + '\n';
  }
  if (!process.env.OPENAI_API_KEY) {
    return null;
  }

  const providerId = envOr('AETHER_EVAL_PROVIDER_ID', 'openai');
  const modelId = envOr('AETHER_EVAL_MODEL_ID', 'gpt-5.5');
  const modelName = envOr('AETHER_EVAL_MODEL_NAME', modelId);
  const wireApi = envOr('AETHER_EVAL_MODEL_API', 'openai');
  const baseUrl = envOr('AETHER_EVAL_MODEL_BASE_URL', 'https://api.openai.com/v1');
  const thinkingLevel = process.env.AETHER_EVAL_DEFAULT_THINKING_LEVEL;
  const contextWindow = positiveIntegerEnv('AETHER_EVAL_CONTEXT_WINDOW');
  const autoCompactLimit = positiveIntegerEnv('AETHER_EVAL_AUTO_COMPACT_TOKEN_LIMIT');

  const lines = [
    `default_provider = ${tomlString(providerId)}`,
    `default_model = ${tomlString(modelId)}`,
  ];
  if (thinkingLevel && thinkingLevel.trim()) {
    lines.push(`default_thinking_level = ${tomlString(thinkingLevel.trim())}`);
  }
  const providerKey = tomlKey(providerId);
  lines.push(
    '',
    `[model_providers.${providerKey}]`,
    `name = ${tomlString(providerId)}`,
    `api = ${tomlString(wireApi)}`,
    `base_url = ${tomlString(baseUrl)}`,
    'api_key = "$OPENAI_API_KEY"',
    '',
    `[[model_providers.${providerKey}.models]]`,
    `id = ${tomlString(modelId)}`,
    `name = ${tomlString(modelName)}`,
    `api = ${tomlString(wireApi)}`,
    `base_url = ${tomlString(baseUrl)}`,
    'input = ["text"]',
  );
  if (contextWindow) {
    lines.push(`context_window = ${contextWindow}`);
  }
  if (autoCompactLimit) {
    lines.push(`auto_compact_token_limit = ${autoCompactLimit}`);
  }
  return lines.join('\n') + '\n';
}

function modelConfigSummary() {
  const hasExplicitConfig = Boolean(process.env.AETHER_CONFIG_TOML);
  const canGenerateOpenAiConfig = Boolean(process.env.OPENAI_API_KEY);
  return {
    source: hasExplicitConfig ? 'AETHER_CONFIG_TOML' : canGenerateOpenAiConfig ? 'generated-openai' : 'missing',
    providerId: envOr('AETHER_EVAL_PROVIDER_ID', 'openai'),
    modelId: envOr('AETHER_EVAL_MODEL_ID', 'gpt-5.5'),
    baseUrl: envOr('AETHER_EVAL_MODEL_BASE_URL', 'https://api.openai.com/v1'),
    thinkingLevel: process.env.AETHER_EVAL_DEFAULT_THINKING_LEVEL || null,
  };
}

function javaToolOptions(home) {
  const existing = process.env.JAVA_TOOL_OPTIONS || '';
  return [`-Duser.home=${home}`, existing].filter(Boolean).join(' ');
}

function ensureAccepted(ack, label) {
  if (!ack || ack.accepted === false) {
    throw new Error(`${label} rejected: ${ack?.message || 'no acknowledgement'}`);
  }
}

function envOr(name, fallback) {
  const value = process.env[name];
  return value && value.trim() ? value.trim() : fallback;
}

function positiveIntegerEnv(name) {
  const value = Number(process.env[name]);
  return Number.isInteger(value) && value > 0 ? value : null;
}

function tomlString(value) {
  return JSON.stringify(String(value));
}

function tomlKey(value) {
  const text = String(value);
  return /^[A-Za-z0-9_-]+$/.test(text) ? text : tomlString(text);
}

function splitArgs(input) {
  return String(input || '').match(/(?:[^\s"]+|"[^"]*")+/g)?.map(part => {
    if (part.startsWith('"') && part.endsWith('"')) {
      return part.slice(1, -1);
    }
    return part;
  }) || [];
}

function numberArg(value, envValue, fallback) {
  const candidate = value ?? envValue;
  const number = Number(candidate);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function toCamel(value) {
  return value.replace(/-([a-z])/g, (_, char) => char.toUpperCase());
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function printHelp() {
  console.log(`Usage:
  node evals/runner/runAetherTask.mjs --instruction "..." [options]
  node evals/runner/runAetherTask.mjs --instruction-file /path/instruction.md [options]

Options:
  --session-cwd PATH          Task workspace. Defaults to AETHER_SESSION_CWD or cwd.
  --backend-cwd PATH          Aether repo/backend cwd. Defaults to repo root.
  --backend-command COMMAND   Backend command. Defaults to AETHER_BACKEND_COMMAND or mvn.
  --backend-args ARGS         Backend args string.
  --permission-mode MODE      Defaults to FULL_ACCESS for sandboxed evals.
  --timeout-seconds N         Defaults to ${DEFAULT_TIMEOUT_SECONDS}.
  --poll-ms N                 Defaults to ${DEFAULT_POLL_MS}.
  --summary-file PATH         Write summary JSON.
  --artifact-dir PATH         Copy summary and ~/.aether into this directory.
  --home PATH                 HOME for Aether state. Defaults to AETHER_EVAL_HOME or HOME.
  --dry-run                   Print resolved config without launching Aether.

Environment:
  AETHER_BIN                  Future native binary path; launched with --stdio.
  AETHER_BACKEND_COMMAND      Override backend command.
  AETHER_BACKEND_ARGS         Override backend args.
  AETHER_BACKEND_CWD          Override backend cwd.
  AETHER_SESSION_CWD          Task workspace.
  AETHER_EVAL_HOME            Isolated HOME for logs/traces/transcripts.
  AETHER_CONFIG_TOML          Full config.toml content to write inside eval HOME.
  AETHER_EVAL_MODEL_ID        Model for generated OpenAI config. Defaults to gpt-5.5.
  AETHER_EVAL_MODEL_BASE_URL  OpenAI-compatible base URL. Defaults to OpenAI.
  OPENAI_API_KEY              Used by generated OpenAI config.
`);
}

await main();
