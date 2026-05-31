import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { StdioTransportOptions } from '../backend/StdioTransport.js';

const moduleRequire = createRequire(import.meta.url);

export function backendOptions(env: NodeJS.ProcessEnv = process.env): StdioTransportOptions {
  const explicitCwd = env.AETHER_BACKEND_CWD;
  const sessionCwd = findSessionCwd(env);

  if (env.AETHER_BACKEND_COMMAND?.trim()) {
    return {
      command: env.AETHER_BACKEND_COMMAND,
      args: env.AETHER_BACKEND_ARGS ? splitArgs(env.AETHER_BACKEND_ARGS) : ['--stdio'],
      cwd: explicitCwd && explicitCwd.trim() ? explicitCwd : process.cwd(),
      sessionCwd,
    };
  }

  const packagedBackend = findPackagedBackend();
  if (packagedBackend) {
    return {
      command: packagedBackend,
      args: ['--stdio'],
      cwd: explicitCwd && explicitCwd.trim() ? explicitCwd : dirname(packagedBackend),
      sessionCwd,
    };
  }

  const cwd = explicitCwd && explicitCwd.trim() ? explicitCwd : findBackendCwd();
  const command = 'mvn';
  const args = ['-q', 'exec:java', '-Dexec.mainClass=io.github.lingjiuu.App', '-Dexec.args=--stdio'];

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

function findPackagedBackend(): string | undefined {
  return findNpmBackend() ?? findLibexecBackend();
}

function findNpmBackend(): string | undefined {
  const packageName = nativeBackendPackageName();
  if (!packageName) {
    return undefined;
  }

  try {
    const packageJsonPath = moduleRequire.resolve(`${packageName}/package.json`);
    const command = resolve(dirname(packageJsonPath), 'bin', backendExecutableName());
    return existsSync(command) ? command : undefined;
  } catch {
    return undefined;
  }
}

function findLibexecBackend(): string | undefined {
  const thisFile = fileURLToPath(import.meta.url);
  const command = resolve(dirname(thisFile), '../../..', backendExecutableName());
  return existsSync(command) ? command : undefined;
}

function nativeBackendPackageName(): string | undefined {
  const platform = process.platform;
  const arch = process.arch;
  if (platform === 'darwin' && arch === 'arm64') {
    return '@lingjiuu/aether-darwin-arm64';
  }
  if (platform === 'darwin' && arch === 'x64') {
    return '@lingjiuu/aether-darwin-x64';
  }
  if (platform === 'win32' && arch === 'x64') {
    return '@lingjiuu/aether-win32-x64';
  }
  return undefined;
}

function backendExecutableName(): string {
  return process.platform === 'win32' ? 'aether-backend.exe' : 'aether-backend';
}

function splitArgs(input: string): string[] {
  return input.split(/\s+/).filter(Boolean);
}
