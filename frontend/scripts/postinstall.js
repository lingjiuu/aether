#!/usr/bin/env node

import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';

const moduleRequire = createRequire(import.meta.url);

const nativePackages = {
  'darwin-arm64': {
    packageName: '@lingjiuu/aether-darwin-arm64',
    executable: 'aether-backend',
  },
  'darwin-x64': {
    packageName: '@lingjiuu/aether-darwin-x64',
    executable: 'aether-backend',
  },
  'win32-x64': {
    packageName: '@lingjiuu/aether-win32-x64',
    executable: 'aether-backend.exe',
  },
};

const platformId = `${process.platform}-${process.arch}`;
const nativePackage = nativePackages[platformId];

if (!nativePackage) {
  fail([
    `Aether does not ship a native backend for ${platformId}.`,
    'Use a supported platform or build the backend from source and set AETHER_BACKEND_COMMAND.',
  ]);
}

let packageJsonPath;
try {
  packageJsonPath = moduleRequire.resolve(`${nativePackage.packageName}/package.json`);
} catch {
  fail([
    `Missing optional dependency: ${nativePackage.packageName}`,
    'Reinstall Aether with optional dependencies enabled:',
    'npm install -g @lingjiuu/aether --include=optional',
    'If npm was configured with --omit=optional or --no-optional, remove that setting first.',
  ]);
}

const executablePath = resolve(dirname(packageJsonPath), 'bin', nativePackage.executable);
if (!existsSync(executablePath)) {
  fail([
    `Native backend executable was not found: ${executablePath}`,
    'Reinstall Aether with optional dependencies enabled:',
    'npm install -g @lingjiuu/aether --include=optional',
  ]);
}

function fail(lines) {
  console.error(['Aether native backend is not installed correctly.', ...lines].join('\n'));
  process.exit(1);
}
