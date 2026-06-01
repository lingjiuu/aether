#!/usr/bin/env node

import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

let child;
let sessionCwd;
let timeout;

const args = parseArgs(process.argv.slice(2));
const backend = requireArg(args, 'backend');
const expectedVersion = args.version;
const repoRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
sessionCwd = mkdtempSync(resolve(tmpdir(), 'aether-native-smoke-'));

let stdout = '';
let stderr = '';
let finished = false;

child = spawn(backend, ['--stdio'], {
  cwd: repoRoot,
  env: {
    ...process.env,
    AETHER_SESSION_CWD: sessionCwd,
  },
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

timeout = setTimeout(() => {
  fail(`Native backend smoke timed out.\nstdout:\n${stdout}\nstderr:\n${stderr}`);
}, 15000);

child.stdout.setEncoding('utf8');
child.stderr.setEncoding('utf8');
child.stdout.on('data', chunk => {
  stdout += chunk;
  checkOutput();
});
child.stderr.on('data', chunk => {
  stderr += chunk;
});
child.on('error', error => {
  fail(`Failed to start native backend: ${error.message}`);
});
child.on('exit', (code, signal) => {
  if (!finished) {
    fail(`Native backend exited before initialize succeeded: code=${code} signal=${signal}\nstdout:\n${stdout}\nstderr:\n${stderr}`);
  }
});

child.stdin.write(`${JSON.stringify({ id: '1', method: 'initialize' })}\n`);

function checkOutput() {
  const lines = stdout.split(/\r?\n/).filter(Boolean);
  for (const line of lines) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      continue;
    }
    if (String(message.id ?? '') !== '1') {
      continue;
    }
    if (message.error) {
      fail(`Native backend initialize returned an error: ${JSON.stringify(message.error)}\nstderr:\n${stderr}`);
    }

    const result = message.result;
    if (result?.protocolVersion !== 'aether.stdio.v1') {
      fail(`Unexpected protocol version: ${JSON.stringify(result?.protocolVersion)}`);
    }
    const actualVersion = result?.session?.appVersion;
    if (expectedVersion && actualVersion !== expectedVersion) {
      fail(`Unexpected app version: expected ${expectedVersion}, got ${JSON.stringify(actualVersion)}`);
    }

    finished = true;
    clearTimeout(timeout);
    child.stdin.end();
    child.kill();
    cleanup();
    console.log(`Native backend smoke passed: ${result.protocolVersion} ${actualVersion ?? ''}`.trim());
    process.exit(0);
  }
}

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      fail(`Unexpected argument: ${arg}`);
    }
    const eq = arg.indexOf('=');
    if (eq !== -1) {
      parsed[arg.slice(2, eq)] = arg.slice(eq + 1);
      continue;
    }
    const key = arg.slice(2);
    const value = argv[++i];
    if (!value || value.startsWith('--')) {
      fail(`--${key} requires a value`);
    }
    parsed[key] = value;
  }
  return parsed;
}

function requireArg(values, name) {
  const value = values[name];
  if (!value) {
    fail(`--${name} is required`);
  }
  return value;
}

function fail(message) {
  finished = true;
  clearTimeout(timeout);
  child?.kill();
  cleanup();
  console.error(message);
  process.exit(1);
}

function cleanup() {
  if (sessionCwd) {
    rmSync(sessionCwd, { recursive: true, force: true });
  }
}
