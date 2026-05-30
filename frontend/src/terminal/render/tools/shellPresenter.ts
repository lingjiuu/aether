import type { UiToolResult } from '../../../protocol/wire.js';
import { compactText, formatDuration } from '../../../utils/format.js';
import type { Details, ToolLine, ToolPresentationDefinition, ToolPresenter, ToolResultView } from './types.js';
import { booleanField, numberField, splitOutputLines, stringField } from './utils.js';

export const bashPresenter: ToolPresenter = {
  userFacingName: () => 'Bash',
  useSummary: args => bashSummary(args),
  progressView(details): ToolResultView | undefined {
    const output = bashOutputLines(details);
    return output.length ? { lines: [...output, { text: 'Running...', tone: 'dim' }] } : undefined;
  },
  resultView: bashResultView,
};

export const shellToolPresentations: ToolPresentationDefinition[] = [
  { names: ['Bash', 'bash'], presenter: bashPresenter },
];

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
