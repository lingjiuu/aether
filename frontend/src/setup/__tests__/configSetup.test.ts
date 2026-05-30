import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ensureAetherConfig } from '../configSetup.js';

describe('ensureAetherConfig', () => {
  it('continues when config already exists', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'aether-config-'));
    const configPath = join(dir, 'config.toml');
    await writeFile(configPath, 'default_provider = "fake"\n');

    await expect(ensureAetherConfig({
      configPath,
      stdin: { isTTY: false } as NodeJS.ReadStream,
      stdout: { isTTY: false } as NodeJS.WriteStream,
    })).resolves.toBe(true);
  });

  it('fails clearly in non-interactive mode when config is missing', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'aether-config-'));
    const configPath = join(dir, 'missing.toml');

    await expect(ensureAetherConfig({
      configPath,
      stdin: { isTTY: false } as NodeJS.ReadStream,
      stdout: { isTTY: false } as NodeJS.WriteStream,
    })).rejects.toThrow(`Aether config not found: ${configPath}`);
  });
});
