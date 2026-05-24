import { renderHelpPanel } from './helpPanelRenderer.js';
import { renderResumePanel } from './resumePanelRenderer.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, commandLine, separator } from '../renderPrimitives.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderCommandPanel(presentation: TerminalPresentation, width: number, maxRows: number): RenderBlock {
  const panel = presentation.commandPanel;
  if (!panel) {
    return { lines: [], cursor: undefined };
  }

  const header: RenderedLine[] = [commandLine(panel.command, width), separator(width), blankLine()];
  const content = panel.kind === 'help'
    ? { lines: renderHelpPanel(width), cursor: undefined }
    : renderResumePanel(presentation, width, maxRows);
  return {
    lines: [...header, ...content.lines],
    cursor: content.cursor ? { x: content.cursor.x, y: header.length + content.cursor.y } : undefined,
  };
}
