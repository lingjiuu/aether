import { afterEach, describe, expect, it, vi } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { backendOptions } from '../bootstrap.js';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('backendOptions', () => {
  it('prefers AETHER_SESSION_CWD over INIT_CWD and process cwd', () => {
    vi.stubEnv('AETHER_SESSION_CWD', '/project/from-session-env');
    vi.stubEnv('INIT_CWD', '/project/from-init-env');
    vi.stubEnv('AETHER_BACKEND_CWD', '/backend/repo');
    vi.stubEnv('AETHER_BACKEND_COMMAND', 'java');
    vi.stubEnv('AETHER_BACKEND_ARGS', '--stdio');
    vi.spyOn(process, 'cwd').mockReturnValue('/project/from-process');

    expect(backendOptions()).toEqual({
      command: 'java',
      args: ['--stdio'],
      cwd: '/backend/repo',
      sessionCwd: '/project/from-session-env',
    });
  });

  it('falls back to INIT_CWD when session cwd is absent', () => {
    vi.stubEnv('INIT_CWD', '/project/from-init-env');
    vi.stubEnv('AETHER_BACKEND_CWD', '/backend/repo');
    vi.stubEnv('AETHER_BACKEND_COMMAND', 'java');
    vi.stubEnv('AETHER_BACKEND_ARGS', '--stdio');
    vi.spyOn(process, 'cwd').mockReturnValue('/project/from-process');

    expect(backendOptions()).toEqual({
      command: 'java',
      args: ['--stdio'],
      cwd: '/backend/repo',
      sessionCwd: '/project/from-init-env',
    });
  });

  it('passes session cwd separately from the backend process cwd', () => {
    vi.stubEnv('INIT_CWD', '/Users/Apple/code/MyProjects/test0');
    vi.spyOn(process, 'cwd').mockReturnValue('/Users/Apple/code/MyProjects/test0');

    const options = backendOptions();

    expect(options.sessionCwd).toBe('/Users/Apple/code/MyProjects/test0');
    expect(options.cwd).not.toBe(options.sessionCwd);
    expect(options.args).not.toContain('-f');
    expect(options.args.at(-1)).toBe('-Dexec.args=--stdio');
  });

  it('does not treat the user cwd as the Aether source backend', () => {
    const projectDir = mkdtempSync(join(tmpdir(), 'aether-user-project-'));
    writeFileSync(join(projectDir, 'pom.xml'), '<project />');
    vi.spyOn(process, 'cwd').mockReturnValue(projectDir);

    try {
      const options = backendOptions();

      expect(options.cwd).not.toBe(projectDir);
      expect(options.command).toBe('mvn');
    } finally {
      rmSync(projectDir, { recursive: true, force: true });
    }
  });

  it('uses an explicit source backend cwd when provided', () => {
    const backendDir = mkdtempSync(join(tmpdir(), 'aether-backend-'));
    writeFileSync(join(backendDir, 'pom.xml'), '<project />');
    vi.stubEnv('AETHER_BACKEND_CWD', backendDir);

    try {
      expect(backendOptions().cwd).toBe(backendDir);
    } finally {
      rmSync(backendDir, { recursive: true, force: true });
    }
  });

  it('defaults explicit backend commands to stdio args', () => {
    vi.stubEnv('AETHER_BACKEND_COMMAND', '/opt/aether/aether-backend');
    vi.stubEnv('AETHER_SESSION_CWD', '/project/from-session-env');
    vi.spyOn(process, 'cwd').mockReturnValue('/project/from-process');

    expect(backendOptions()).toEqual({
      command: '/opt/aether/aether-backend',
      args: ['--stdio'],
      cwd: '/project/from-process',
      sessionCwd: '/project/from-session-env',
    });
  });
});
