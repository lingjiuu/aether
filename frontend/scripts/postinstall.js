#!/usr/bin/env node

import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';

const moduleRequire = createRequire(import.meta.url);

const backendPackages = {
  'darwin-arm64': {
    packageName: '@lingjiuu/aether-darwin-arm64',
  },
  'darwin-x64': {
    packageName: '@lingjiuu/aether-darwin-x64',
  },
  'win32-x64': {
    packageName: '@lingjiuu/aether-win32-x64',
  },
};

const platformId = `${process.platform}-${process.arch}`;
const backendPackage = backendPackages[platformId];

if (!backendPackage) {
  fail([
    `Aether does not ship a bundled JVM backend for ${platformId}.`,
    'Use a supported platform or build the backend from source and set AETHER_BACKEND_COMMAND.',
  ]);
}

let packageJsonPath;
try {
  packageJsonPath = moduleRequire.resolve(`${backendPackage.packageName}/package.json`);
} catch {
  fail([
    `Missing optional dependency: ${backendPackage.packageName}`,
    'Reinstall Aether with optional dependencies enabled:',
    'npm install -g @lingjiuu/aether --include=optional',
    'If npm was configured with --omit=optional or --no-optional, remove that setting first.',
  ]);
}

const packageDir = dirname(packageJsonPath);
const javaPath = resolve(packageDir, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const jarPath = resolve(packageDir, 'backend', 'aether-backend.jar');

if (!existsSync(javaPath)) {
  fail([
    `Bundled Java launcher was not found: ${javaPath}`,
    'Reinstall Aether with optional dependencies enabled:',
    'npm install -g @lingjiuu/aether --include=optional',
  ]);
}

if (!existsSync(jarPath)) {
  fail([
    `Backend jar was not found: ${jarPath}`,
    'Reinstall Aether with optional dependencies enabled:',
    'npm install -g @lingjiuu/aether --include=optional',
  ]);
}

function fail(lines) {
  console.error(['Aether bundled JVM backend is not installed correctly.', ...lines].join('\n'));
  process.exit(1);
}
