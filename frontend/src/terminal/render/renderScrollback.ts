import { bottomLineCount, renderBottom } from './bottomRenderer.js';
import type { TerminalPresentation } from './presentationModel.js';
import { block, line, resetRenderKeySequence } from './renderPrimitives.js';
import { dim } from '../shared/ansi.js';
import { renderTranscriptSections } from './transcriptRenderer.js';
import type { RenderedLine } from './viewModel.js';
import type { TerminalView } from './viewModel.js';

type RenderOptions = {
  presentation: TerminalPresentation;
  columns: number;
  rows: number;
  composerCursorOffset: number;
  approvalSelectedIndex?: number;
};

export function renderScrollback({
  presentation,
  columns,
  rows,
  composerCursorOffset,
  approvalSelectedIndex = 0,
}: RenderOptions): TerminalView {
  resetRenderKeySequence();
  const width = Math.max(20, columns - 1);
  const transcript = renderTranscriptSections(presentation, width);
  const bottom = renderBottom({
    presentation,
    width,
    rows,
    composerCursorOffset,
    approvalSelectedIndex,
  });
  const activeTranscriptLines = fitActiveTranscriptToViewport(
    transcript.active,
    Math.max(0, rows - bottomLineCount(bottom)),
    width,
  );
  const history = block('history', transcript.history);
  const activeTranscript = block('active-transcript', activeTranscriptLines);
  const active = block('active', [activeTranscript, bottom]);
  const frame = block('terminal-frame', [history, active]);
  const cursor = bottom.cursor
    ? { x: bottom.cursor.x, y: activeTranscriptLines.length + bottom.cursor.y }
    : undefined;

  return {
    resetKey: `transcript:${presentation.transcriptEpoch}`,
    frame,
    history,
    active,
    cursor,
  };
}

function fitActiveTranscriptToViewport(lines: RenderedLine[], maxRows: number, width: number): RenderedLine[] {
  if (lines.length <= maxRows) {
    return lines;
  }
  if (maxRows <= 0) {
    return [];
  }
  if (maxRows === 1) {
    return lines.slice(-1);
  }

  return [
    line(dim('...'), '...', width, 'active-truncation'),
    ...lines.slice(-(maxRows - 1)),
  ];
}
