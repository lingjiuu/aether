#!/usr/bin/env node

import { copyFileSync, cpSync, existsSync, mkdirSync, rmSync, chmodSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const frontendRoot = resolve(repoRoot, 'frontend');

const platforms = {
  'darwin-arm64': {
    packageName: '@lingjiuu/aether-darwin-arm64',
    os: 'darwin',
    cpu: 'arm64',
    executable: 'aether-backend',
  },
  'darwin-x64': {
    packageName: '@lingjiuu/aether-darwin-x64',
    os: 'darwin',
    cpu: 'x64',
    executable: 'aether-backend',
  },
  'win32-x64': {
    packageName: '@lingjiuu/aether-win32-x64',
    os: 'win32',
    cpu: 'x64',
    executable: 'aether-backend.exe',
  },
};
const windowsRuntimeDlls = ['VCRUNTIME140.dll', 'VCRUNTIME140_1.dll'];

const args = parseArgs(process.argv.slice(2));
const version = requireArg(args, 'version');
const packageKind = args.package ?? 'all';
const outRoot = resolve(repoRoot, args.outDir ?? args['out-dir'] ?? 'target/npm');
const stageRoot = resolve(outRoot, 'stage');
const distRoot = resolve(outRoot, 'dist');

if (!['all', 'main', 'platform'].includes(packageKind)) {
  fail('--package must be one of: all, main, platform');
}

rmSync(stageRoot, { recursive: true, force: true });
rmSync(distRoot, { recursive: true, force: true });
mkdirSync(stageRoot, { recursive: true });
mkdirSync(distRoot, { recursive: true });

const staged = [];
if (packageKind === 'all' || packageKind === 'main') {
  staged.push(stageMainPackage(version, stageRoot));
}
if (packageKind === 'all' || packageKind === 'platform') {
  const platform = requireArg(args, 'platform');
  const backend = requireArg(args, 'backend');
  staged.push(stagePlatformPackage(version, platform, backend, stageRoot));
}

if (args.pack) {
  for (const packageDir of staged) {
    run(npmCommand(), ['pack', packageDir, '--pack-destination', distRoot], repoRoot);
  }
}

for (const packageDir of staged) {
  console.log(packageDir);
}

function stageMainPackage(version, root) {
  const packageDir = resolve(root, 'aether');
  mkdirSync(packageDir, { recursive: true });

  const sourcePackageJson = readJson(resolve(frontendRoot, 'package.json'));
  const packageJson = {
    name: '@lingjiuu/aether',
    version,
    description: sourcePackageJson.description,
    type: 'module',
    bin: { aether: 'dist/main.js' },
    files: ['dist', 'scripts'],
    scripts: {
      postinstall: 'node scripts/postinstall.js',
    },
    engines: sourcePackageJson.engines,
    dependencies: sourcePackageJson.dependencies,
    optionalDependencies: Object.fromEntries(
      Object.values(platforms).map(platform => [platform.packageName, version]),
    ),
    repository: {
      type: 'git',
      url: 'git+https://github.com/lingjiuu/aether.git',
    },
  };

  const distDir = resolve(frontendRoot, 'dist');
  if (!existsSync(resolve(distDir, 'main.js'))) {
    fail('frontend/dist/main.js does not exist. Run `pnpm build` in frontend first.');
  }

  cpSync(distDir, resolve(packageDir, 'dist'), { recursive: true });
  mkdirSync(resolve(packageDir, 'scripts'), { recursive: true });
  copyFileSync(resolve(frontendRoot, 'scripts', 'postinstall.js'), resolve(packageDir, 'scripts', 'postinstall.js'));
  writeJson(resolve(packageDir, 'package.json'), packageJson);
  return packageDir;
}

function stagePlatformPackage(version, platformName, backendPath, root) {
  const platform = platforms[platformName];
  if (!platform) {
    fail(`Unsupported platform: ${platformName}. Expected one of: ${Object.keys(platforms).join(', ')}`);
  }

  const backend = resolveInputPath(backendPath);
  console.error(`Staging ${platformName} backend from ${backend}`);
  if (!existsSync(backend)) {
    fail(`Backend executable does not exist: ${backend}`);
  }

  const packageDir = resolve(root, platform.packageName.replace('@lingjiuu/', ''));
  const binDir = resolve(packageDir, 'bin');
  mkdirSync(binDir, { recursive: true });

  const executablePath = resolve(binDir, platform.executable);
  copyFileSync(backend, executablePath);
  if (platform.os !== 'win32') {
    chmodSync(executablePath, 0o755);
  } else {
    copyWindowsRuntimeDlls(binDir);
  }

  const sourcePackageJson = readJson(resolve(frontendRoot, 'package.json'));
  const packageJson = {
    name: platform.packageName,
    version,
    description: `Aether native backend for ${platformName}`,
    os: [platform.os],
    cpu: [platform.cpu],
    files: ['bin'],
    engines: sourcePackageJson.engines,
    repository: {
      type: 'git',
      url: 'git+https://github.com/lingjiuu/aether.git',
    },
  };
  writeJson(resolve(packageDir, 'package.json'), packageJson);
  return packageDir;
}

function copyWindowsRuntimeDlls(binDir) {
  if (process.platform !== 'win32') {
    console.warn('Skipping Windows VC runtime DLL copy because staging is not running on Windows.');
    return;
  }

  for (const dllName of windowsRuntimeDlls) {
    const dllPath = findWindowsRuntimeDll(dllName);
    if (!dllPath) {
      fail(`Could not find ${dllName}. Install Microsoft Visual C++ Redistributable or set AETHER_WINDOWS_RUNTIME_DIR.`);
    }
    const target = resolve(binDir, basename(dllName));
    copyFileSync(dllPath, target);
    console.error(`Bundled Windows runtime DLL: ${dllPath}`);
  }
}

function findWindowsRuntimeDll(dllName) {
  for (const directory of windowsRuntimeSearchDirectories()) {
    const candidate = resolve(directory, dllName);
    if (existsSync(candidate)) {
      return candidate;
    }
  }

  const where = spawnSync('where.exe', [dllName], { encoding: 'utf8' });
  if (where.status === 0) {
    return where.stdout
      .split(/\r?\n/)
      .map(line => line.trim())
      .find(Boolean);
  }

  return undefined;
}

function windowsRuntimeSearchDirectories() {
  const directories = [];
  const redistRoots = [];
  const explicit = process.env.AETHER_WINDOWS_RUNTIME_DIR;
  if (explicit?.trim()) {
    directories.push(explicit.trim());
  }

  const systemRoot = process.env.SystemRoot || 'C:\\Windows';
  directories.push(join(systemRoot, 'System32'));

  for (const base of [
    process.env.ProgramFiles,
    process.env['ProgramFiles(x86)'],
  ]) {
    if (!base) {
      continue;
    }
    redistRoots.push(
      join(base, 'Microsoft Visual Studio', '2022', 'Enterprise', 'VC', 'Redist', 'MSVC'),
      join(base, 'Microsoft Visual Studio', '2022', 'Community', 'VC', 'Redist', 'MSVC'),
      join(base, 'Microsoft Visual Studio', '2022', 'BuildTools', 'VC', 'Redist', 'MSVC'),
    );
  }

  return [
    ...directories,
    ...redistRoots,
    ...redistRoots.flatMap(directory => redistSubdirectories(directory)),
  ];
}

function redistSubdirectories(directory) {
  try {
    return readdirSync(directory, { withFileTypes: true })
      .filter(entry => entry.isDirectory())
      .flatMap(entry => [
        resolve(directory, entry.name),
        resolve(directory, entry.name, 'x64', 'Microsoft.VC143.CRT'),
        resolve(directory, entry.name, 'x64', 'Microsoft.VC142.CRT'),
      ]);
  } catch {
    return [];
  }
}

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--pack') {
      parsed.pack = true;
      continue;
    }
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

function requireArg(args, name) {
  const value = args[name];
  if (!value) {
    fail(`--${name} is required`);
  }
  return value;
}

function resolveInputPath(path) {
  return isAbsolute(path) ? path : resolve(repoRoot, path);
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.error) {
    console.error(result.error.message);
    process.exit(1);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function npmCommand() {
  return 'npm';
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
