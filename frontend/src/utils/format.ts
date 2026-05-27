import truncate from 'cli-truncate';

export function compactText(text: string | null | undefined, width = 100): string {
  const singleLine = (text ?? '').replace(/\s+/g, ' ').trim();
  return truncate(singleLine, width);
}

export function formatDuration(durationMs?: number | null): string {
  if (durationMs == null) {
    return '';
  }
  if (durationMs < 1000) {
    return `${durationMs}ms`;
  }
  return `${(durationMs / 1000).toFixed(1)}s`;
}

export function formatElapsedTime(startedAtMs?: number | null, endedAtMs?: number | null): string {
  if (startedAtMs == null || endedAtMs == null || endedAtMs < startedAtMs) {
    return '';
  }
  const seconds = Math.max(1, Math.round((endedAtMs - startedAtMs) / 1000));
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return remainder ? `${minutes}m ${remainder}s` : `${minutes}m`;
}

export function formatJsonPreview(json?: string | null, width = 120): string {
  if (!json) {
    return '';
  }
  try {
    return compactText(JSON.stringify(JSON.parse(json)), width);
  } catch {
    return compactText(json, width);
  }
}

export function formatToolUseSummary(toolName: string, argumentsJson?: string | null, width = 160): string {
  if (!argumentsJson) {
    return '';
  }
  try {
    const parsed = JSON.parse(argumentsJson) as unknown;
    if (!isRecord(parsed)) {
      return compactText(String(parsed), width);
    }
    const summary = toolSpecificSummary(toolName, parsed) ?? JSON.stringify(parsed);
    return compactText(summary, width);
  } catch {
    return compactText(argumentsJson, width);
  }
}

export function tailLines(text: string | null | undefined, limit = 5): string[] {
  return stripAnsi(text ?? '')
    .trim()
    .split('\n')
    .map(line => line.trimEnd())
    .filter(Boolean)
    .slice(-limit);
}

export function formatLineStatus(text: string | null | undefined, displayedLineCount: number): string {
  const totalLines = stripAnsi(text ?? '')
    .trim()
    .split('\n')
    .filter(Boolean).length;
  const hiddenLines = Math.max(0, totalLines - displayedLineCount);
  return hiddenLines > 0 ? `+${hiddenLines} lines` : '';
}

export function normalizeNoOutput(text: string | null | undefined): string {
  const normalized = (text ?? '').trim();
  return normalized.toLowerCase() === '(no output)' ? '(No output)' : normalized;
}

function toolSpecificSummary(toolName: string, args: Record<string, unknown>): string | undefined {
  switch (toolName) {
    case 'bash':
      return stringArg(args, 'command');
    case 'read':
    case 'write':
    case 'edit':
      return stringArg(args, 'file_path');
    case 'ls':
      return stringArg(args, 'path') ?? '.';
    case 'glob': {
      const pattern = stringArg(args, 'pattern');
      const path = stringArg(args, 'path');
      return [pattern, path ? `in ${path}` : undefined].filter(Boolean).join(' ');
    }
    case 'grep': {
      const pattern = stringArg(args, 'pattern');
      const path = stringArg(args, 'path');
      const glob = stringArg(args, 'glob');
      return [pattern, path ? `in ${path}` : undefined, glob ? `(${glob})` : undefined].filter(Boolean).join(' ');
    }
    default:
      return undefined;
  }
}

function stringArg(args: Record<string, unknown>, name: string): string | undefined {
  const value = args[name];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stripAnsi(text: string): string {
  // eslint-disable-next-line no-control-regex
  return text.replace(/\u001b\[[0-9;]*m/g, '');
}
