import { renderBottom } from './bottomRenderer.js';
import type { TerminalPresentation } from './presentationModel.js';
import { clampNumber } from '../shared/terminalMath.js';
import { renderTranscript } from './transcriptRenderer.js';
import type { TerminalView } from './viewModel.js';

type RenderOptions = {
  presentation: TerminalPresentation;
  columns: number;
  rows: number;
  composerCursorOffset: number;
  transcriptScrollTop?: number;
  approvalSelectedIndex?: number;
};

export function renderScrollback({
  presentation,
  columns,
  rows,
  composerCursorOffset,
  transcriptScrollTop,
  approvalSelectedIndex = 0,
}: RenderOptions): TerminalView {
  const width = Math.max(20, columns - 1);
  const transcript = renderTranscript(presentation, width);
  const bottom = renderBottom({
    presentation,
    width,
    rows,
    composerCursorOffset,
    approvalSelectedIndex,
  });
  const transcriptRows = Math.max(1, rows - bottom.lines.length);
  const maxScrollTop = Math.max(0, transcript.length - transcriptRows);
  const scrollTop = clampNumber(transcriptScrollTop ?? maxScrollTop, 0, maxScrollTop);
  const transcriptLines = transcript.slice(scrollTop, scrollTop + transcriptRows);
  const cursor = bottom.cursor
    ? { x: bottom.cursor.x, y: transcriptLines.length + bottom.cursor.y }
    : undefined;

  return {
    resetKey: `transcript:${presentation.transcriptEpoch}`,
    transcriptLines: transcriptLines.map(rendered => rendered.text),
    bottomLines: bottom.lines.map(rendered => rendered.text),
    scroll: {
      scrollTop,
      maxScrollTop,
      viewportRows: transcriptRows,
      transcriptLineCount: transcript.length,
      isAtBottom: scrollTop >= maxScrollTop,
    },
    cursor,
  };
}
