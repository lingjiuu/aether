import type { UiToolResult } from '../../../protocol/wire.js';
import { compactText, formatDuration } from '../../../utils/format.js';
import { defaultToolErrorView } from './resultPresenter.js';
import type { Details, ToolLine, ToolPresentationDefinition, ToolPresenter, ToolResultView } from './types.js';
import { booleanField, displayText, effectiveDurationMs, numberField, splitOutputLines, stringField } from './utils.js';

export const bashPresenter: ToolPresenter = {
  userFacingName: toolName => (toolName.toLowerCase() === 'powershell' ? 'PowerShell' : 'Bash'),
  useSummary: args => bashSummary(args),
  progressView(details): ToolResultView | undefined {
    const output = bashOutputLines(details);
    return output.length ? { lines: [...output, { text: 'Running...', tone: 'dim' }] } : undefined;
  },
  errorView: bashErrorView,
  resultView: bashResultView,
};

export const shellToolPresentations: ToolPresentationDefinition[] = [
  { names: ['Bash', 'bash', 'PowerShell', 'powershell'], presenter: bashPresenter },
];

function bashResultView(details: Details, result: UiToolResult): ToolResultView {
  const output = bashOutputLines(details);
  if (output.length) {
    return { lines: withTruncationLines(details, output) };
  }

  const duration = formatDuration(numberField(details, 'durationMs') ?? effectiveDurationMs(result));
  return { lines: [{ text: `Done${duration ? ` ${duration}` : ''}`, tone: 'dim' }] };
}

function bashErrorView(details: Details, result: UiToolResult): ToolResultView {
  const output = bashOutputLines(details);
  if (output.length) {
    const lines = withTruncationLines(details, output);
    const message = displayText(result);
    if (message && !lines.some(line => line.text === message)) {
      lines.push({ text: message, tone: 'error' });
    }
    return { lines };
  }

  return defaultToolErrorView(result);
}

function bashSummary(details: Details | undefined): string | undefined {
  const command = stringField(details, 'command');
  return command ? compactText(command, 160) : undefined;
}

function bashOutputLines(details: Details): ToolLine[] {
  return [
    ...splitOutputLines(stringField(details, 'stdout')).map(text => ({ text, tone: 'normal' as const })),
    ...splitOutputLines(stringField(details, 'stderr')).map(text => ({ text, tone: 'error' as const })),
  ];
}

function withTruncationLines(details: Details, output: ToolLine[]): ToolLine[] {
  const lines = [...output];
  if (booleanField(details, 'stdoutTruncated')) {
    lines.push({ text: 'stdout truncated', tone: 'dim' });
  }
  if (booleanField(details, 'stderrTruncated')) {
    lines.push({ text: 'stderr truncated', tone: 'dim' });
  }
  return lines;
}
