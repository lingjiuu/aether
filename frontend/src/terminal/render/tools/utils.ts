import type { UiToolCall, UiToolResult, UiToolUpdate } from '../../../protocol/wire.js';
import path from 'node:path';
import { formatToolUseSummary } from '../../../utils/format.js';
import { stripAnsi } from '../../shared/text.js';
import type { Details } from './types.js';

export function pathSummary(args: Details | undefined, toolCall: UiToolCall | undefined, _toolName?: string, cwd?: string | null): string | undefined {
  const value = stringField(args, 'path') ?? stringField(args, 'file_path') ?? clean(toolCall?.displaySummary);
  return displayPath(value, cwd);
}

export function searchSummary(args: Details | undefined, fallback?: string): string | undefined {
  const pattern = stringField(args, 'pattern');
  if (!pattern) {
    return fallback;
  }

  const path = stringField(args, 'path');
  const glob = stringField(args, 'glob');
  return [
    `pattern: ${JSON.stringify(pattern)}`,
    path ? `in ${path}` : undefined,
    glob ? `(${glob})` : undefined,
  ].filter(Boolean).join(' ');
}

export function defaultToolUseSummary(
  args: Details | undefined,
  toolCall: UiToolCall | undefined,
  toolName: string,
): string | undefined {
  return clean(toolCall?.displaySummary)
    ?? formatToolUseSummary(toolCall?.toolName ?? toolName, toolCall?.argumentsJson, 160)
    ?? stringField(args, 'path')
    ?? stringField(args, 'file_path')
    ?? stringField(args, 'command');
}

export function displayPath(value: string | undefined, cwd?: string | null): string | undefined {
  if (!value || !path.isAbsolute(value) || !cwd?.trim()) {
    return value;
  }

  const root = path.resolve(cwd);
  const absolute = path.resolve(value);
  const relative = path.relative(root, absolute);
  if (!relative) {
    return '.';
  }
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    return value;
  }
  return relative;
}

export function defaultUserFacingName(toolName: string, toolCall: UiToolCall | undefined): string {
  return clean(toolCall?.displayName) ?? (toolName || 'tool');
}

export function detailsKind(
  details: Details | undefined,
  source: UiToolResult | UiToolUpdate | undefined,
  toolCall: UiToolCall | undefined,
): string {
  return clean(stringField(details, 'kind'))
    ?? clean(displayKind(source?.display))
    ?? clean(source?.toolName)
    ?? clean(toolCall?.toolName)
    ?? clean(toolCall?.displayName)
    ?? 'tool';
}

export function displayDetails(source: UiToolResult | UiToolUpdate | undefined): Details | undefined {
  const display = detailsRecord(source?.display);
  return detailsRecord(display?.data) ?? detailsRecord(source?.details);
}

function displayKind(displayValue: unknown): string | undefined {
  const display = detailsRecord(displayValue);
  return stringField(display, 'kind');
}

export function toolArguments(toolCall: UiToolCall | undefined): Details | undefined {
  const direct = detailsRecord(toolCall?.arguments);
  if (direct) {
    return direct;
  }

  const json = toolCall?.argumentsJson;
  if (!json) {
    return undefined;
  }
  try {
    return detailsRecord(JSON.parse(json));
  } catch {
    return undefined;
  }
}

export function splitOutputLines(text: string | undefined): string[] {
  return stripAnsi(text ?? '')
    .split('\n')
    .map(line => line.trimEnd())
    .filter(line => line.length > 0);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function plural(count: number, singular: string, pluralText: string): string {
  return count === 1 ? singular : pluralText;
}

export function detailsRecord(value: unknown): Details | undefined {
  return isRecord(value) ? value : undefined;
}

export function stringField(details: Details | undefined, field: string): string | undefined {
  const value = details?.[field];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

export function numberField(details: Details | undefined, field: string): number | undefined {
  const value = details?.[field];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

export function booleanField(details: Details | undefined, field: string): boolean {
  return details?.[field] === true;
}

export function clean(text: string | null | undefined): string | undefined {
  const trimmed = text?.trim();
  return trimmed ? trimmed : undefined;
}

function isRecord(value: unknown): value is Details {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
