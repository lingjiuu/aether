import { renderHelpPanel } from './helpPanelRenderer.js';
import { renderModelPanel } from './modelPanelRenderer.js';
import { renderResumePanel } from './resumePanelRenderer.js';
import { renderSkillsPanel } from './skillsPanelRenderer.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, block, commandLine, separator } from '../renderPrimitives.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderCommandPanel(presentation: TerminalPresentation, width: number, maxRows: number): RenderBlock {
  const panel = presentation.commandPanel;
  if (!panel) {
    return block('command-panel');
  }

  const header: RenderedLine[] = [commandLine(panel.command, width), separator(width), blankLine()];
  const content = panel.kind === 'help'
    ? block('help-panel', renderHelpPanel(width))
    : panel.kind === 'resume'
      ? renderResumePanel(presentation, width, maxRows)
      : panel.kind === 'skills'
        ? renderSkillsPanel(presentation, width, maxRows)
        : renderModelPanel(presentation, width, maxRows);
  return block(
    `command-panel:${panel.kind}`,
    [...header, content],
    content.cursor ? { x: content.cursor.x, y: header.length + content.cursor.y } : undefined,
  );
}
