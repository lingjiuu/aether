import type { ToolLine, ToolLineTone, ToolPresenter, ToolResultView } from './types.js';
import { fallbackResultView } from './fallbackPresenter.js';
import {
  formatBytes,
  numberField,
  pathSummary,
  plural,
  splitOutputLines,
  stringField,
} from './utils.js';

export const readPresenter: ToolPresenter = {
  userFacingName: () => 'Read',
  useSummary: pathSummary,
  resultView(details, result): ToolResultView {
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
  },
};

export const writePresenter: ToolPresenter = {
  userFacingName: () => 'Write',
  useSummary: pathSummary,
  resultView(details): ToolResultView {
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
  },
};

export const editPresenter: ToolPresenter = {
  userFacingName: () => 'Update',
  useSummary: pathSummary,
  resultView(details): ToolResultView {
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
  },
};

function diffTone(line: string): ToolLineTone {
  if (line.startsWith('-')) {
    return 'error';
  }
  if (line.startsWith('+')) {
    return 'success';
  }
  return 'dim';
}
