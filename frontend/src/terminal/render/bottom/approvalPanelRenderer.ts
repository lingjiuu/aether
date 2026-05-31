import { accent, dim, error, success } from '../../shared/ansi.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, block, line, separator } from '../renderPrimitives.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { truncatePlain, wrapPlain } from '../../shared/text.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderApprovalPanel(
  presentation: TerminalPresentation,
  width: number,
  selectedIndex: number,
  maxRows = 18,
): RenderBlock {
  const request = presentation.pendingApproval?.request;
  if (!request) {
    return block('approval-panel');
  }

  const toolName = request.toolName ?? 'tool';
  const choiceIndex = clampIndex(selectedIndex, 2);
  const input = inputSummary(toolName, request.arguments, width, Math.max(2, maxRows - 7));
  const lines: RenderedLine[] = [separator(width), ...section(input.title, input.lines, width), blankLine()];

  lines.push(line(`  ${dim('Do you want to allow this?')}`, '  Do you want to allow this?', width));
  const approveY = lines.length;
  lines.push(optionLine('1. Yes, proceed (y)', choiceIndex === 0, 'approve', width));
  lines.push(optionLine('2. No, deny (esc)', choiceIndex === 1, 'deny', width));
  lines.push(blankLine());
  lines.push(line(`  ${dim('Enter confirm · ↑/↓ select · y yes · n no')}`, '  Enter confirm · ↑/↓ select · y yes · n no', width));
  lines.push(separator(width));

  return block('approval-panel', lines, { x: 0, y: approveY + choiceIndex });
}

function section(label: string, bodyLines: string[], width: number): RenderedLine[] {
  return [
    line(`  ${accent(label)}`, `  ${label}`, width),
    ...bodyLines.map(text => line(`    ${text}`, `    ${text}`, width)),
  ];
}

function optionLine(label: string, selected: boolean, tone: 'approve' | 'deny', width: number): RenderedLine {
  const marker = selected ? '❯ ' : '  ';
  const raw = `${marker}${label}`;
  const selectedText = tone === 'approve' ? success(label) : error(label);
  return line(`${selected ? accent(marker) : marker}${selected ? selectedText : label}`, raw, width);
}

function inputSummary(
  toolName: string,
  args: Record<string, unknown> | null | undefined,
  width: number,
  maxLines: number,
): { title: string; lines: string[] } {
  if (isShellTool(toolName)) {
    return { title: `${titleCase(toolName)} Command`, lines: commandLines(commandArgument(args), width, maxLines) };
  }
  return { title: `${titleCase(toolName)} Tool`, lines: argumentLines(args, width, maxLines) };
}

function commandArgument(args?: Record<string, unknown> | null): string {
  const command = args?.command;
  return typeof command === 'string' && command.trim() ? command : formatApprovalArguments(args);
}

function commandLines(command: string, width: number, maxLines: number): string[] {
  const available = Math.max(10, width - 8);
  const wrapped = wrapPlain(`$ ${command || '(no command provided)'}`, available, '  ');
  return clampLines(wrapped, Math.max(1, maxLines), available);
}

function argumentLines(args: Record<string, unknown> | null | undefined, width: number, maxLines: number): string[] {
  if (!args || !Object.keys(args).length) {
    return ['No arguments provided'];
  }
  const available = Math.max(10, width - 8);
  const rows = Object.entries(args).flatMap(([key, value]) => {
    const text = `${key}: ${formatValue(value)}`;
    return wrapPlain(text, available, '  ');
  });
  return clampLines(rows, Math.max(1, maxLines), available);
}

function clampLines(lines: string[], maxLines: number, width: number): string[] {
  if (lines.length <= maxLines) {
    return lines;
  }
  const kept = lines.slice(0, Math.max(1, maxLines));
  const last = kept.at(-1) ?? '';
  kept[kept.length - 1] = truncatePlain(`${last}...`, Math.max(3, width));
  return kept;
}

function formatApprovalArguments(args?: Record<string, unknown> | null): string {
  if (!args || !Object.keys(args).length) {
    return '';
  }
  return truncatePlain(JSON.stringify(args), 160);
}

function formatValue(value: unknown): string {
  if (value === null) {
    return 'null';
  }
  if (typeof value === 'string') {
    return truncatePlain(value, 160);
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  const json = JSON.stringify(value);
  return truncatePlain(json ?? String(value), 160);
}

function isShellTool(toolName: string): boolean {
  return ['bash', 'powershell'].includes(toolName.toLowerCase());
}

function titleCase(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return 'Tool';
  }
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
}
