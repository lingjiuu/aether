import { accent, bold, dim, userLine } from '../shared/ansi.js';
import { padPlain, truncatePlain, visualWidth } from '../shared/text.js';
import type { RenderedLine } from './viewModel.js';

export const PROMPT = '❯ ';

export function commandLine(command: string, width: number): RenderedLine {
  const raw = `${PROMPT}${command}`;
  return line(userLine(`${PROMPT}${bold(command)}`), raw, width);
}

export function userMessageLine(message: string, width: number): RenderedLine {
  const raw = truncatePlain(`${PROMPT}${message}`, width);
  return { text: userLine(padPlain(raw, width)), raw };
}

export function separator(width: number): RenderedLine {
  return line(dim('─'.repeat(width)), '─'.repeat(width), width);
}

export function blankLine(): RenderedLine {
  return line('', '', Number.MAX_SAFE_INTEGER);
}

export function line(text: string, raw: string, width: number): RenderedLine {
  if (visualWidth(raw) > width) {
    const truncated = truncatePlain(raw, width);
    return { text: truncated, raw: truncated };
  }
  return { text, raw };
}

export function selectedLine(label: string, selected: boolean, width: number): RenderedLine {
  const marker = selected ? '› ' : '  ';
  const raw = `${marker}${label}`;
  return line(selected ? accent(raw) : raw, raw, width);
}
