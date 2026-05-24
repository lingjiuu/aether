import type { TimelineItem } from '../domain/timeline.js';
import type { UiToolCall, UiToolResult, UiToolUpdate } from '../protocol/wire.js';
import { formatDuration, formatToolUseSummary, tailLines } from '../utils/format.js';
import { stripAnsi } from './text.js';

export type ToolLineTone = 'normal' | 'dim' | 'error' | 'success';

export type ToolLine = {
  text: string;
  tone?: ToolLineTone;
};

export type ToolUseView = {
  name: string;
  summary?: string;
};

export type ToolResultView = {
  lines: ToolLine[];
};

type Details = Record<string, unknown>;

export function toolUseView(item: TimelineItem): ToolUseView {
  const toolCall = item.toolCall;
  const toolName = normalizedToolName(item);
  const args = toolArguments(toolCall)
    ?? detailsRecord(item.toolResult?.details)
    ?? detailsRecord(item.toolUpdate?.details);
  const name = userFacingName(toolName, toolCall);
  const summary = toolUseSummary(toolName, args, toolCall);
  return summary ? { name, summary } : { name };
}

export function toolProgressView(item: TimelineItem): ToolResultView {
  const update = item.toolUpdate;
  const details = detailsRecord(update?.details) ?? {};
  if (detailsKind(details, update, item.toolCall) === 'bash') {
    const output = bashOutputLines(details);
    if (output.length) {
      return { lines: [...output, { text: 'Running...', tone: 'dim' }] };
    }
  }

  const updateText = clean(update?.text);
  return { lines: [{ text: updateText || 'Running...', tone: 'dim' }] };
}

export function toolResultView(item: TimelineItem): ToolResultView {
  const result = item.toolResult;
  if (!result) {
    return { lines: [] };
  }

  const details = detailsRecord(result.details) ?? {};
  switch (detailsKind(details, result, item.toolCall)) {
    case 'read':
      return readResultView(details, result);
    case 'ls':
      return countResultView(details, 'entryCount', 'Listed', 'entry', 'entries', 'Listed results');
    case 'grep':
      return grepResultView(details);
    case 'find':
      return countResultView(details, 'resultCount', 'Found', 'file', 'files', 'Found results');
    case 'bash':
      return bashResultView(details, result);
    case 'write':
      return writeResultView(details);
    case 'edit':
      return editResultView(details);
    default:
      return fallbackResultView(result);
  }
}

function readResultView(details: Details, result: UiToolResult): ToolResultView {
  const fileType = stringField(details, 'fileType');
  if (fileType === 'image') {
    const bytes = numberField(details, 'bytes');
    return { lines: [{ text: bytes == null ? 'Read image' : `Read image (${formatBytes(bytes)})`, tone: 'dim' }] };
  }

  const lineCount = numberField(details, 'returnedLines') ?? numberField(details, 'totalLines');
  if (lineCount != null) {
    return { lines: [{ text: `Read ${lineCount} ${plural(lineCount, 'line', 'lines')}`, tone: 'dim' }] };
  }

  const fallback = fallbackResultView(result);
  return fallback.lines.length ? fallback : { lines: [{ text: 'Read file', tone: 'dim' }] };
}

function grepResultView(details: Details): ToolResultView {
  const matchCount = numberField(details, 'matchCount');
  const fileCount = numberField(details, 'fileCount');
  if (matchCount != null && fileCount != null) {
    return {
      lines: [
        {
          text: `Found ${matchCount} ${plural(matchCount, 'match', 'matches')} across ${fileCount} ${plural(fileCount, 'file', 'files')}`,
          tone: 'dim',
        },
      ],
    };
  }
  if (matchCount != null) {
    return { lines: [{ text: `Found ${matchCount} ${plural(matchCount, 'match', 'matches')}`, tone: 'dim' }] };
  }
  return { lines: [{ text: 'Search completed', tone: 'dim' }] };
}

function bashResultView(details: Details, result: UiToolResult): ToolResultView {
  const output = bashOutputLines(details);
  if (output.length) {
    const lines = [...output];
    if (booleanField(details, 'stdoutTruncated')) {
      lines.push({ text: 'stdout truncated', tone: 'dim' });
    }
    if (booleanField(details, 'stderrTruncated')) {
      lines.push({ text: 'stderr truncated', tone: 'dim' });
    }
    return { lines };
  }

  const duration = formatDuration(numberField(details, 'durationMs') ?? result.durationMs);
  const status = result.error ? 'Error' : 'Done';
  return { lines: [{ text: `${status}${duration ? ` ${duration}` : ''}`, tone: result.error ? 'error' : 'dim' }] };
}

function writeResultView(details: Details): ToolResultView {
  const path = stringField(details, 'path') ?? 'file';
  const lineCount = numberField(details, 'lineCount');
  if (lineCount != null) {
    return { lines: [{ text: `Wrote ${lineCount} ${plural(lineCount, 'line', 'lines')} to ${path}`, tone: 'dim' }] };
  }

  const chars = numberField(details, 'chars');
  if (chars != null) {
    return { lines: [{ text: `Wrote ${chars} ${plural(chars, 'char', 'chars')} to ${path}`, tone: 'dim' }] };
  }
  return { lines: [{ text: `Wrote ${path}`, tone: 'dim' }] };
}

function editResultView(details: Details): ToolResultView {
  const path = stringField(details, 'path') ?? 'file';
  const editCount = numberField(details, 'editCount');
  const summary = editCount == null
    ? `Updated ${path}`
    : `Updated ${path} with ${editCount} ${plural(editCount, 'edit', 'edits')}`;
  const lines: ToolLine[] = [{ text: summary, tone: 'dim' }];

  for (const diffLine of splitOutputLines(stringField(details, 'diffText'))) {
    lines.push({ text: diffLine, tone: diffTone(diffLine) });
  }
  return { lines };
}

function countResultView(
  details: Details,
  field: string,
  verb: string,
  singular: string,
  pluralText: string,
  fallback: string,
): ToolResultView {
  const count = numberField(details, field);
  if (count == null) {
    return { lines: [{ text: fallback, tone: 'dim' }] };
  }
  return { lines: [{ text: `${verb} ${count} ${plural(count, singular, pluralText)}`, tone: 'dim' }] };
}

function fallbackResultView(result: UiToolResult): ToolResultView {
  const output = tailLines(result.text, 5);
  if (output.length) {
    return { lines: output.map(text => ({ text, tone: result.error ? 'error' : 'dim' })) };
  }

  const duration = formatDuration(result.durationMs);
  const status = result.error ? 'Error' : 'Done';
  return { lines: [{ text: `${status}${duration ? ` ${duration}` : ''}`, tone: result.error ? 'error' : 'dim' }] };
}

function bashOutputLines(details: Details): ToolLine[] {
  return [
    ...splitOutputLines(stringField(details, 'stdout')).map(text => ({ text, tone: 'normal' as const })),
    ...splitOutputLines(stringField(details, 'stderr')).map(text => ({ text, tone: 'error' as const })),
  ];
}

function toolUseSummary(
  toolName: string,
  args: Details | undefined,
  toolCall: UiToolCall | undefined,
): string | undefined {
  switch (toolName) {
    case 'read':
    case 'write':
    case 'edit':
      return stringField(args, 'path') ?? clean(toolCall?.displaySummary);
    case 'ls':
      return stringField(args, 'path') ?? clean(toolCall?.displaySummary) ?? '.';
    case 'bash':
      return stringField(args, 'command') ?? clean(toolCall?.displaySummary);
    case 'grep':
      return searchSummary(args, clean(toolCall?.displaySummary));
    case 'find':
      return searchSummary(args, clean(toolCall?.displaySummary));
    default:
      return clean(toolCall?.displaySummary)
        ?? formatToolUseSummary(toolCall?.toolName ?? toolName, toolCall?.argumentsJson, 160);
  }
}

function searchSummary(args: Details | undefined, fallback?: string): string | undefined {
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

function userFacingName(toolName: string, toolCall: UiToolCall | undefined): string {
  switch (toolName) {
    case 'read':
      return 'Read';
    case 'grep':
    case 'find':
      return 'Search';
    case 'bash':
      return 'Bash';
    case 'write':
      return 'Write';
    case 'edit':
      return 'Update';
    case 'ls':
      return 'ls';
    default:
      return clean(toolCall?.displayName) ?? (toolName || 'tool');
  }
}

function normalizedToolName(item: TimelineItem): string {
  return clean(item.toolCall?.toolName)
    ?? clean(item.toolResult?.toolName)
    ?? clean(item.toolUpdate?.toolName)
    ?? clean(item.toolCall?.displayName)
    ?? 'tool';
}

function detailsKind(
  details: Details | undefined,
  source: UiToolResult | UiToolUpdate | undefined,
  toolCall: UiToolCall | undefined,
): string {
  return clean(stringField(details, 'kind'))
    ?? clean(source?.toolName)
    ?? clean(toolCall?.toolName)
    ?? clean(toolCall?.displayName)
    ?? 'tool';
}

function toolArguments(toolCall: UiToolCall | undefined): Details | undefined {
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

function splitOutputLines(text: string | undefined): string[] {
  return stripAnsi(text ?? '')
    .split('\n')
    .map(line => line.trimEnd())
    .filter(line => line.length > 0);
}

function diffTone(line: string): ToolLineTone {
  if (line.startsWith('-')) {
    return 'error';
  }
  if (line.startsWith('+')) {
    return 'success';
  }
  return 'dim';
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function plural(count: number, singular: string, pluralText: string): string {
  return count === 1 ? singular : pluralText;
}

function detailsRecord(value: unknown): Details | undefined {
  return isRecord(value) ? value : undefined;
}

function stringField(details: Details | undefined, field: string): string | undefined {
  const value = details?.[field];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function numberField(details: Details | undefined, field: string): number | undefined {
  const value = details?.[field];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function booleanField(details: Details | undefined, field: string): boolean {
  return details?.[field] === true;
}

function clean(text: string | null | undefined): string | undefined {
  const trimmed = text?.trim();
  return trimmed ? trimmed : undefined;
}

function isRecord(value: unknown): value is Details {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
