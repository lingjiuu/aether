import { renderApprovalPanel } from './bottom/approvalPanelRenderer.js';
import { renderCommandPanel } from './bottom/commandPanelRenderer.js';
import { renderComposer } from './bottom/composerRenderer.js';
import { renderFooter } from './bottom/footerRenderer.js';
import type { BottomRenderOptions } from './bottom/types.js';
import type { RenderBlock } from './viewModel.js';

export function renderBottom({
  presentation,
  width,
  rows,
  composerCursorOffset,
  approvalSelectedIndex,
}: BottomRenderOptions): RenderBlock {
  const footer = renderFooter(presentation, width);
  const bottomBudget = Math.max(6, rows - footer.length);
  const panel = presentation.commandPanel
    ? renderCommandPanel(presentation, width, bottomBudget)
    : presentation.pendingApproval
      ? renderApprovalPanel(presentation, width, approvalSelectedIndex)
      : renderComposer(presentation, width, composerCursorOffset);

  return {
    lines: [...panel.lines, ...footer],
    cursor: panel.cursor,
  };
}
