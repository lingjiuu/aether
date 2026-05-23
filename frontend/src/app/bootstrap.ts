import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { StdioTransportOptions } from '../backend/StdioTransport.js';

export function backendOptions(env: NodeJS.ProcessEnv = process.env): StdioTransportOptions {
  const explicitCwd = env.AETHER_BACKEND_CWD;
  const cwd = explicitCwd && explicitCwd.trim() ? explicitCwd : findBackendCwd();
  const sessionCwd = findSessionCwd(env);
  const command = env.AETHER_BACKEND_COMMAND || 'mvn';
  const args = env.AETHER_BACKEND_ARGS
    ? splitArgs(env.AETHER_BACKEND_ARGS)
    : ['-q', 'exec:java', '-Dexec.mainClass=io.github.lingjiuu.App', '-Dexec.args=--stdio'];

  return { command, args, cwd, sessionCwd };
}

function findSessionCwd(env: NodeJS.ProcessEnv): string {
  const explicit = env.AETHER_SESSION_CWD;
  if (explicit && explicit.trim()) {
    return explicit;
  }

  const initCwd = env.INIT_CWD;
  if (initCwd && initCwd.trim()) {
    return initCwd;
  }

  return process.cwd();
}

function findBackendCwd(): string {
  const current = process.cwd();
  if (existsSync(resolve(current, 'pom.xml'))) {
    return current;
  }

  const parent = resolve(current, '..');
  if (existsSync(resolve(parent, 'pom.xml'))) {
    return parent;
  }

  const thisFile = fileURLToPath(import.meta.url);
  const repoRoot = resolve(dirname(thisFile), '../../..');
  if (existsSync(resolve(repoRoot, 'pom.xml'))) {
    return repoRoot;
  }

  return current;
}

function splitArgs(input: string): string[] {
  return input.split(/\s+/).filter(Boolean);
}
