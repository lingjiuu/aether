import { bottomLineCount, renderBottom } from './bottomRenderer.js';
import type { TerminalPresentation } from './presentationModel.js';
import { block, resetRenderKeySequence } from './renderPrimitives.js';
import { clampNumber } from '../shared/terminalMath.js';
import { renderTranscript } from './transcriptRenderer.js';
import type { TerminalView } from './viewModel.js';

type RenderOptions = {
  presentation: TerminalPresentation;
  columns: number;
  rows: number;
  composerCursorOffset: number;
  transcriptScrollTop?: number;
  pendingDeltaRows?: number;
  approvalSelectedIndex?: number;
};

export function renderScrollback({
  presentation,
  columns,
  rows,
  composerCursorOffset,
  transcriptScrollTop,
  pendingDeltaRows = 0,
  approvalSelectedIndex = 0,
}: RenderOptions): TerminalView {
  resetRenderKeySequence();
  const width = Math.max(20, columns - 1);
  const transcript = renderTranscript(presentation, width);
  const bottom = renderBottom({
    presentation,
    width,
    rows,
    composerCursorOffset,
    approvalSelectedIndex,
  });
  const bottomRows = bottomLineCount(bottom);
  const transcriptRows = Math.max(1, rows - bottomRows);
  const maxScrollTop = Math.max(0, transcript.length - transcriptRows);
  const scrollTop = clampNumber(transcriptScrollTop ?? maxScrollTop, 0, maxScrollTop);
  const transcriptLines = transcript.slice(scrollTop, scrollTop + transcriptRows);
  const transcriptBlock = block('transcript', transcriptLines);
  const frame = block('terminal-frame', [transcriptBlock, bottom]);
  const cursor = bottom.cursor
    ? { x: bottom.cursor.x, y: transcriptLines.length + bottom.cursor.y }
    : undefined;

  return {
    resetKey: `transcript:${presentation.transcriptEpoch}`,
    frame,
    transcript: transcriptBlock,
    bottom,
    scroll: {
      scrollTop,
      maxScrollTop,
      viewportRows: transcriptRows,
      transcriptLineCount: transcript.length,
      isAtBottom: scrollTop >= maxScrollTop,
      isSticky: transcriptScrollTop === undefined || scrollTop >= maxScrollTop,
      pendingDeltaRows,
    },
    cursor,
  };
}
