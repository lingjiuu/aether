import { dim } from '../../shared/ansi.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { line } from '../renderPrimitives.js';
import type { RenderedLine } from '../viewModel.js';

export function renderFooter(presentation: TerminalPresentation, width: number): RenderedLine[] {
  if (presentation.commandPanel) {
    return [];
  }
  const left = presentation.pendingApproval ? 'esc to deny' : presentation.isRunning ? 'esc to interrupt' : '? for shortcuts';
  const notice = presentation.notices.at(-1);
  const text = notice ? `${left}  ${notice}` : left;
  return [line(dim(text), text, width)];
}
