import type { ToolLine, ToolLineTone, ToolPresentationDefinition, ToolPresenter, ToolResultView } from './types.js';
import { fallbackResultView } from './fallbackPresenter.js';
import {
  displayPath,
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
  resultView(details, result, cwd): ToolResultView {
    if (result.error) {
      return fallbackResultView(result);
    }
    const path = displayPath(stringField(details, 'path'), cwd) ?? 'file';
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
  resultView(details, result, cwd): ToolResultView {
    if (result.error) {
      return fallbackResultView(result);
    }
    const path = displayPath(stringField(details, 'path'), cwd) ?? 'file';
    const editCount = numberField(details, 'editCount');
    const diffLines = compactDiffLines(splitOutputLines(stringField(details, 'diffText')));
    const changed = diffStats(diffLines.all);
    const summary = editCount == null
      ? `Updated ${path}`
      : `Updated ${path} with ${editCount} ${plural(editCount, 'edit', 'edits')}`;
    const lines: ToolLine[] = [{ text: summary, tone: 'dim' }];
    if (changed.total > 0) {
      lines.push({ text: `${changed.added} added, ${changed.removed} removed`, tone: 'dim' });
    }

    for (const diffLine of diffLines.visible) {
      lines.push({ text: diffLine, tone: diffTone(diffLine) });
    }
    return { lines };
  },
};

export const fileToolPresentations: ToolPresentationDefinition[] = [
  { names: ['Read', 'read'], presenter: readPresenter },
  { names: ['Write', 'write'], presenter: writePresenter },
  { names: ['Edit', 'edit'], presenter: editPresenter },
];

function diffTone(line: string): ToolLineTone {
  if (line.startsWith('-')) {
    return 'error';
  }
  if (line.startsWith('+')) {
    return 'success';
  }
  return 'dim';
}

function compactDiffLines(lines: string[]): { all: string[]; visible: string[] } {
  const maxLines = 14;
  if (lines.length <= maxLines) {
    return { all: lines, visible: lines };
  }

  const headCount = 5;
  const tailCount = 5;
  return {
    all: lines,
    visible: [
      ...lines.slice(0, headCount),
      `... ${lines.length - headCount - tailCount} diff lines hidden`,
      ...lines.slice(-tailCount),
    ],
  };
}

function diffStats(lines: string[]): { added: number; removed: number; total: number } {
  const added = lines.filter(diffLine => diffLine.startsWith('+')).length;
  const removed = lines.filter(diffLine => diffLine.startsWith('-')).length;
  return { added, removed, total: added + removed };
}
