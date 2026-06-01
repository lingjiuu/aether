import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { StdioTransportOptions } from '../backend/StdioTransport.js';

const moduleRequire = createRequire(import.meta.url);

type PackagedBackend = {
  packageDir: string;
  javaCommand: string;
  jarPath: string;
};

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
      command: packagedBackend.javaCommand,
      args: ['-jar', packagedBackend.jarPath, '--stdio'],
      cwd: explicitCwd && explicitCwd.trim() ? explicitCwd : packagedBackend.packageDir,
      sessionCwd,
    };
  }

  const sourceBackendCwd = findSourceBackendCwd(explicitCwd);
  if (sourceBackendCwd) {
    const command = 'mvn';
    const args = ['-q', 'exec:java', '-Dexec.mainClass=io.github.lingjiuu.App', '-Dexec.args=--stdio'];

    return { command, args, cwd: sourceBackendCwd, sessionCwd };
  }

  throw new Error(missingBackendMessage());
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

function findSourceBackendCwd(explicitCwd?: string): string | undefined {
  const explicit = explicitCwd?.trim();
  if (explicit && existsSync(resolve(explicit, 'pom.xml'))) {
    return explicit;
  }

  const thisFile = fileURLToPath(import.meta.url);
  const repoRoot = resolve(dirname(thisFile), '../../..');
  if (existsSync(resolve(repoRoot, 'pom.xml'))) {
    return repoRoot;
  }

  return undefined;
}

function findPackagedBackend(): PackagedBackend | undefined {
  return findNpmBackend() ?? findLibexecBackend();
}

function findNpmBackend(): PackagedBackend | undefined {
  const packageName = backendPackageName();
  if (!packageName) {
    return undefined;
  }

  try {
    const packageJsonPath = moduleRequire.resolve(`${packageName}/package.json`);
    return packagedBackendFromDirectory(dirname(packageJsonPath));
  } catch {
    return undefined;
  }
}

function findLibexecBackend(): PackagedBackend | undefined {
  const thisFile = fileURLToPath(import.meta.url);
  return packagedBackendFromDirectory(resolve(dirname(thisFile), '../../..'));
}

function packagedBackendFromDirectory(packageDir: string): PackagedBackend | undefined {
  const javaCommand = resolve(packageDir, 'runtime', 'bin', javaExecutableName());
  const jarPath = resolve(packageDir, 'backend', 'aether-backend.jar');
  if (!existsSync(javaCommand) || !existsSync(jarPath)) {
    return undefined;
  }
  return { packageDir, javaCommand, jarPath };
}

function backendPackageName(): string | undefined {
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

function javaExecutableName(): string {
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function missingBackendMessage(): string {
  const platformId = `${process.platform}-${process.arch}`;
  const packageName = backendPackageName();
  if (!packageName) {
    return [
      `Aether does not ship a bundled JVM backend for ${platformId}.`,
      'Build the backend from source and set AETHER_BACKEND_COMMAND, or use a supported platform.',
    ].join('\n');
  }

  return [
    `Aether bundled JVM backend package is missing for ${platformId}.`,
    `Expected optional dependency: ${packageName}`,
    'Reinstall Aether with optional dependencies enabled:',
    'npm install -g @lingjiuu/aether --include=optional',
    'If npm was configured with --omit=optional or --no-optional, remove that setting first.',
  ].join('\n');
}

function splitArgs(input: string): string[] {
  return input.split(/\s+/).filter(Boolean);
}
