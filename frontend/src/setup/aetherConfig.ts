import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';

export type AetherConfigSetupValues = {
  providerId: string;
  baseUrl: string;
  apiKey: string;
  modelId: string;
  contextWindowK?: string;
  modelName?: string;
  thinkingLevel?: string;
};

export function aetherConfigPath(home = homedir()): string {
  return resolve(home, '.aether', 'config.toml');
}

export function hasAetherConfig(configPath = aetherConfigPath()): boolean {
  return existsSync(configPath);
}

export async function writeAetherConfig(
  values: AetherConfigSetupValues,
  configPath = aetherConfigPath(),
): Promise<void> {
  await mkdir(dirname(configPath), { recursive: true, mode: 0o700 });
  await writeFile(configPath, aetherConfigToml(values), { flag: 'wx', mode: 0o600 });
}

export function aetherConfigToml(values: AetherConfigSetupValues): string {
  const providerId = values.providerId.trim();
  const modelId = normalized(values.modelId, 'gpt-5.5');
  const modelName = normalized(values.modelName, modelId);
  const thinkingLevel = normalized(values.thinkingLevel, 'medium');
  const contextWindow = contextWindowTokens(values.contextWindowK);
  const baseUrl = values.baseUrl.trim();
  const apiKey = values.apiKey.trim();
  const providerKey = tomlKey(providerId);

  return [
    `default_provider = ${tomlString(providerId)}`,
    `default_model = ${tomlString(modelId)}`,
    `default_thinking_level = ${tomlString(thinkingLevel)}`,
    '',
    `[model_providers.${providerKey}]`,
    `name = ${tomlString(providerId)}`,
    'api = "openai"',
    `base_url = ${tomlString(baseUrl)}`,
    `api_key = ${tomlString(apiKey)}`,
    '',
    `[[model_providers.${providerKey}.models]]`,
    `id = ${tomlString(modelId)}`,
    `name = ${tomlString(modelName)}`,
    'api = "openai"',
    `base_url = ${tomlString(baseUrl)}`,
    `context_window = ${contextWindow}`,
    'input = ["text", "image"]',
    '',
  ].join('\n');
}

function normalized(value: string | undefined, fallback: string): string {
  const trimmed = value?.trim();
  return trimmed || fallback;
}

function contextWindowTokens(value: string | undefined): number {
  const parsed = Number.parseInt(normalized(value, '258'), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed * 1000 : 258000;
}

function tomlString(value: string): string {
  return JSON.stringify(value);
}

function tomlKey(value: string): string {
  return /^[A-Za-z0-9_-]+$/.test(value) ? value : tomlString(value);
}
