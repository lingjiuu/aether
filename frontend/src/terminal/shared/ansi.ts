export const ansi = {
  clearVisible: '\x1b[2J',
  eraseDown: '\x1b[J',
  clearScrollback: '\x1b[3J',
  eraseLineRight: '\x1b[K',
  home: '\x1b[H',
  hideCursor: '\x1b[?25l',
  showCursor: '\x1b[?25h',
  syncStart: '\x1b[?2026h',
  syncEnd: '\x1b[?2026l',
  reset: '\x1b[0m',
  bold: '\x1b[1m',
  italic: '\x1b[3m',
  underline: '\x1b[4m',
  dim: '\x1b[2m',
  cyan: '\x1b[36m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  red: '\x1b[31m',
  userBackground: '\x1b[48;2;48;50;58m',
  userMessageBackground: '\x1b[48;2;67;70;86m',
};

export function cursorTo(x: number, y: number): string {
  return `\x1b[${Math.max(1, y + 1)};${Math.max(1, x + 1)}H`;
}

export function cursorMove(x: number, y: number): string {
  return `${moveAxis(y, 'B', 'A')}${moveAxis(x, 'C', 'D')}`;
}

function moveAxis(delta: number, positive: string, negative: string): string {
  if (delta === 0) {
    return '';
  }
  return `\x1b[${Math.abs(delta)}${delta > 0 ? positive : negative}`;
}

export function bold(text: string): string {
  return `${ansi.bold}${text}${ansi.reset}`;
}

export function dim(text: string): string {
  return `${ansi.dim}${text}${ansi.reset}`;
}

export function accent(text: string): string {
  return `${ansi.cyan}${text}${ansi.reset}`;
}

export function success(text: string): string {
  return `${ansi.green}${text}${ansi.reset}`;
}

export function warning(text: string): string {
  return `${ansi.yellow}${text}${ansi.reset}`;
}

export function error(text: string): string {
  return `${ansi.red}${text}${ansi.reset}`;
}

export function userLine(text: string): string {
  return `${ansi.userBackground}${text}${ansi.reset}`;
}

export function userMessageLine(text: string): string {
  return `${ansi.userMessageBackground}${text}${ansi.reset}`;
}
