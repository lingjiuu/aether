const ANSI_PATTERN = /\x1b\[[0-9;?]*[ -/]*[@-~]/g;

export function stripAnsi(text: string): string {
  return text.replace(ANSI_PATTERN, '');
}

export function visualWidth(text: string): number {
  let width = 0;
  for (const char of stripAnsi(text)) {
    width += charWidth(char);
  }
  return width;
}

export function charWidth(char: string): number {
  const codePoint = char.codePointAt(0) ?? 0;
  if (codePoint === 0) {
    return 0;
  }
  if (codePoint < 32 || (codePoint >= 0x7f && codePoint < 0xa0)) {
    return 0;
  }
  return isWideCodePoint(codePoint) ? 2 : 1;
}

export function truncatePlain(text: string, width: number): string {
  if (width <= 0) {
    return '';
  }

  let used = 0;
  let output = '';
  for (const char of text) {
    const next = charWidth(char);
    if (used + next > width) {
      break;
    }
    output += char;
    used += next;
  }
  return output;
}

export function padPlain(text: string, width: number): string {
  const visible = visualWidth(text);
  if (visible >= width) {
    return text;
  }
  return `${text}${' '.repeat(width - visible)}`;
}

export function fitPlain(text: string, width: number): string {
  return padPlain(truncatePlain(text, width), width);
}

export function wrapPlain(text: string, width: number, subsequentIndent = ''): string[] {
  if (width <= 0) {
    return [''];
  }

  const lines: string[] = [];
  for (const sourceLine of text.split('\n')) {
    if (!sourceLine) {
      lines.push('');
      continue;
    }

    let current = '';
    let currentWidth = 0;
    for (const char of sourceLine) {
      const charDisplayWidth = charWidth(char);
      const availableWidth = lines.length ? width - visualWidth(subsequentIndent) : width;
      if (current && currentWidth + charDisplayWidth > availableWidth) {
        lines.push(current);
        current = subsequentIndent;
        currentWidth = visualWidth(current);
      }
      current += char;
      currentWidth += charDisplayWidth;
    }
    lines.push(current);
  }
  return lines;
}

function isWideCodePoint(codePoint: number): boolean {
  return (
    (codePoint >= 0x1100 && codePoint <= 0x115f) ||
    (codePoint >= 0x2329 && codePoint <= 0x232a) ||
    (codePoint >= 0x2e80 && codePoint <= 0xa4cf) ||
    (codePoint >= 0xac00 && codePoint <= 0xd7a3) ||
    (codePoint >= 0xf900 && codePoint <= 0xfaff) ||
    (codePoint >= 0xfe10 && codePoint <= 0xfe19) ||
    (codePoint >= 0xfe30 && codePoint <= 0xfe6f) ||
    (codePoint >= 0xff00 && codePoint <= 0xff60) ||
    (codePoint >= 0xffe0 && codePoint <= 0xffe6) ||
    (codePoint >= 0x1f300 && codePoint <= 0x1f64f) ||
    (codePoint >= 0x1f900 && codePoint <= 0x1f9ff)
  );
}
