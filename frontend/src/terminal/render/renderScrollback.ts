import { renderBottom } from './bottomRenderer.js';
import type { TerminalPresentation } from './presentationModel.js';
import { block, resetRenderKeySequence } from './renderPrimitives.js';
import { renderTranscriptSections } from './transcriptRenderer.js';
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
  const history = block('history', transcript.history);
  const activeTranscript = block('active-transcript', transcript.active);
  const active = block('active', [activeTranscript, bottom]);
  const frame = block('terminal-frame', [history, active]);
  const cursor = bottom.cursor
    ? { x: bottom.cursor.x, y: transcript.active.length + bottom.cursor.y }
    : undefined;

  return {
    resetKey: `transcript:${presentation.transcriptEpoch}`,
    frame,
    history,
    active,
    cursor,
  };
}
