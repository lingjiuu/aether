import { accent, bold, dim, userLine, userMessageLine as userMessageLineStyle } from '../shared/ansi.js';
import { padPlain, truncatePlain, visualWidth } from '../shared/text.js';
import type { RenderBlock, RenderNode, RenderedLine } from './viewModel.js';

export const PROMPT = '❯ ';
export const CARD_MAX_INNER_WIDTH = 56;

let lineSequence = 0;

export function resetRenderKeySequence(): void {
  lineSequence = 0;
}

export function block(key: string, children: RenderNode[] = [], cursor?: RenderBlock['cursor']): RenderBlock {
  return {
    kind: 'block',
    key,
    children,
    cursor,
  };
}

export function commandLine(command: string, width: number): RenderedLine {
  const raw = `${PROMPT}${command}`;
  return line(userLine(`${PROMPT}${bold(command)}`), raw, width);
}

export function userMessageLine(message: string, width: number): RenderedLine {
  const raw = truncatePlain(`${PROMPT}${message}`, width);
  return renderLine(userMessageLineStyle(padPlain(raw, width)), raw, width, 'user-message');
}

export function separator(width: number): RenderedLine {
  return line(dim('─'.repeat(width)), '─'.repeat(width), width);
}

export function blankLine(): RenderedLine {
  return line('', '', Number.MAX_SAFE_INTEGER);
}

export function line(text: string, raw: string, width: number, role?: string): RenderedLine {
  return renderLine(text, raw, width, role);
}

export type CardRow = {
  text: string;
  raw: string;
};

export function cardInnerWidth(width: number, maxInnerWidth = CARD_MAX_INNER_WIDTH): number | undefined {
  if (width < 4) {
    return undefined;
  }
  return Math.min(width - 4, maxInnerWidth);
}

export function borderedCard(rows: CardRow[], width: number, role = 'card'): RenderedLine[] {
  const innerWidth = cardInnerWidth(width);
  if (innerWidth === undefined) {
    return rows.map(row => line(row.text, row.raw, width, role));
  }

  const fittedRows = rows.map(row => fitCardRow(row, innerWidth));
  const contentWidth = Math.max(0, ...fittedRows.map(row => visualWidth(row.raw)));
  const borderWidth = contentWidth + 2;
  const output: RenderedLine[] = [];
  const top = `╭${'─'.repeat(borderWidth)}╮`;
  output.push(line(dim(top), top, width, role));

  for (const row of fittedRows) {
    const padding = ' '.repeat(contentWidth - visualWidth(row.raw));
    const raw = `│ ${row.raw}${padding} │`;
    const text = `${dim('│ ')}${row.text}${dim(`${padding} │`)}`;
    output.push(line(text, raw, width, role));
  }

  const bottom = `╰${'─'.repeat(borderWidth)}╯`;
  output.push(line(dim(bottom), bottom, width, role));
  return output;
}

export function flattenLines(node: RenderNode): RenderedLine[] {
  return node.kind === 'line' ? [node] : node.children.flatMap(flattenLines);
}

function renderLine(text: string, raw: string, width: number, role?: string): RenderedLine {
  const key = `${role ?? 'line'}:${lineSequence++}`;
  if (visualWidth(raw) > width) {
    const truncated = truncatePlain(raw, width);
    return { kind: 'line', key, text: truncated, raw: truncated, role };
  }
  return { kind: 'line', key, text, raw, role };
}

function fitCardRow(row: CardRow, width: number): CardRow {
  if (visualWidth(row.raw) <= width) {
    return row;
  }
  const raw = truncatePlain(row.raw, width);
  return { raw, text: raw };
}

export function selectedLine(label: string, selected: boolean, width: number): RenderedLine {
  const marker = selected ? '› ' : '  ';
  const raw = `${marker}${label}`;
  return line(selected ? accent(raw) : raw, raw, width);
}
