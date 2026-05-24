import { dim, warning } from '../../shared/ansi.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, line, selectedLine, separator } from '../renderPrimitives.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { truncatePlain, wrapPlain } from '../../shared/text.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderApprovalPanel(presentation: TerminalPresentation, width: number, selectedIndex: number): RenderBlock {
  const request = presentation.pendingApproval?.request;
  if (!request) {
    return { lines: [], cursor: undefined };
  }

  const toolName = request.toolName ?? 'tool';
  const risk = request.riskLevel ? `Risk: ${request.riskLevel}` : undefined;
  const reason = request.reason?.trim() ? `Reason: ${request.reason.trim()}` : undefined;
  const args = formatApprovalArguments(request.arguments);
  const choiceIndex = clampIndex(selectedIndex, 2);
  const lines: RenderedLine[] = [
    separator(width),
    blankLine(),
    line(warning(`Approval required: ${toolName}`), `Approval required: ${toolName}`, width),
  ];

  for (const detail of [risk, reason, args ? `Arguments: ${args}` : undefined].filter(Boolean)) {
    lines.push(...wrapPlain(detail ?? '', width).map(text => line(dim(text), text, width)));
  }

  lines.push(blankLine());
  const approveY = lines.length;
  lines.push(selectedLine('Approve', choiceIndex === 0, width), selectedLine('Deny', choiceIndex === 1, width), blankLine());
  lines.push(line(dim('Up/Down to select · Enter to confirm · Esc to deny'), 'Up/Down to select · Enter to confirm · Esc to deny', width));
  lines.push(separator(width));

  return { lines, cursor: { x: 0, y: approveY + choiceIndex } };
}

function formatApprovalArguments(args?: Record<string, unknown> | null): string {
  if (!args || !Object.keys(args).length) {
    return '';
  }
  return truncatePlain(JSON.stringify(args), 160);
}
