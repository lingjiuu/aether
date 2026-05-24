import { accent, bold, dim, userLine } from '../shared/ansi.js';
import { padPlain, truncatePlain, visualWidth } from '../shared/text.js';
import type { RenderBlock, RenderNode, RenderedLine } from './viewModel.js';

export const PROMPT = '❯ ';

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
  return renderLine(userLine(padPlain(raw, width)), raw, width, 'user-message');
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

export function selectedLine(label: string, selected: boolean, width: number): RenderedLine {
  const marker = selected ? '› ' : '  ';
  const raw = `${marker}${label}`;
  return line(selected ? accent(raw) : raw, raw, width);
}
