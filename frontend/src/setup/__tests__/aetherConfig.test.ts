import { mkdtemp, readFile, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { aetherConfigPath, aetherConfigToml, writeAetherConfig } from '../aetherConfig.js';

describe('aether config setup', () => {
  it('renders a valid OpenAI-compatible config', () => {
    const toml = aetherConfigToml({
      providerId: 'lingsuan-openai',
      baseUrl: 'https://example.test/v1',
      apiKey: 'sk-test',
      modelId: 'gpt-test',
    });

    expect(toml).toContain('default_provider = "lingsuan-openai"');
    expect(toml).toContain('default_model = "gpt-test"');
    expect(toml).toContain('[model_providers.lingsuan-openai]');
    expect(toml).toContain('base_url = "https://example.test/v1"');
    expect(toml).toContain('api_key = "sk-test"');
    expect(toml).toContain('context_window = 258000');
    expect(toml).toContain('input = ["text", "image"]');
  });

  it('writes context window from K units', () => {
    const toml = aetherConfigToml({
      providerId: 'lingsuan-openai',
      baseUrl: 'https://example.test/v1',
      apiKey: 'sk-test',
      modelId: 'gpt-test',
      contextWindowK: '256',
    });

    expect(toml).toContain('context_window = 256000');
  });

  it('escapes TOML string values', () => {
    const toml = aetherConfigToml({
      providerId: 'provider.with.dot',
      baseUrl: 'https://example.test/"quoted"',
      apiKey: 'sk-"secret"',
      modelId: 'model\\id',
    });

    expect(toml).toContain('[model_providers."provider.with.dot"]');
    expect(toml).toContain('default_model = "model\\\\id"');
    expect(toml).toContain('api_key = "sk-\\"secret\\""');
  });

  it('writes config under the Aether home', async () => {
    const home = await mkdtemp(join(tmpdir(), 'aether-home-'));
    const configPath = aetherConfigPath(home);

    await writeAetherConfig({
      providerId: 'lingsuan-openai',
      baseUrl: 'https://example.test/v1',
      apiKey: 'sk-test',
      modelId: 'gpt-test',
    }, configPath);

    await expect(readFile(configPath, 'utf8')).resolves.toContain('api_key = "sk-test"');
    expect((await stat(configPath)).mode & 0o777).toBe(0o600);
  });
});
