import { charWidth } from '../shared/text.js';

export type Key =
  | { kind: 'text'; value: string }
  | { kind: 'return' }
  | { kind: 'tab' }
  | { kind: 'backspace' }
  | { kind: 'escape' }
  | { kind: 'up' }
  | { kind: 'down' }
  | { kind: 'left' }
  | { kind: 'right' }
  | { kind: 'page-up' }
  | { kind: 'page-down' }
  | { kind: 'home' }
  | { kind: 'end' }
  | { kind: 'wheel-up' }
  | { kind: 'wheel-down' }
  | { kind: 'ctrl-c' }
  | { kind: 'ctrl-a' }
  | { kind: 'ctrl-e' };

const FIXED_ESCAPE_SEQUENCES: Array<{ sequence: string; key: Key }> = [
  { sequence: '\x1b[A', key: { kind: 'up' } },
  { sequence: '\x1b[B', key: { kind: 'down' } },
  { sequence: '\x1b[C', key: { kind: 'right' } },
  { sequence: '\x1b[D', key: { kind: 'left' } },
  { sequence: '\x1b[5~', key: { kind: 'page-up' } },
  { sequence: '\x1b[6~', key: { kind: 'page-down' } },
  { sequence: '\x1b[H', key: { kind: 'home' } },
  { sequence: '\x1b[1~', key: { kind: 'home' } },
  { sequence: '\x1b[7~', key: { kind: 'home' } },
  { sequence: '\x1b[F', key: { kind: 'end' } },
  { sequence: '\x1b[4~', key: { kind: 'end' } },
  { sequence: '\x1b[8~', key: { kind: 'end' } },
];

export class KeyParser {
  private incomplete = '';

  parse(chunk: string | Buffer): Key[] {
    const input = this.incomplete + String(chunk);
    this.incomplete = '';
    return this.parseInput(input);
  }

  flush(): Key[] {
    if (!this.incomplete) {
      return [];
    }
    const input = this.incomplete;
    this.incomplete = '';
    return this.parseInput(input, true);
  }

  private parseInput(input: string, flush = false): Key[] {
    const keys: Key[] = [];
    for (let index = 0; index < input.length; ) {
      const rest = input.slice(index);
      const mouse = rest.match(/^\x1b\[<(\d+);\d+;\d+[mM]/);
      if (mouse) {
        const buttonCode = Number(mouse[1]);
        const buttonWithoutModifiers = buttonCode & ~0b11100;
        if (buttonWithoutModifiers === 64) {
          keys.push({ kind: 'wheel-up' });
        } else if (buttonWithoutModifiers === 65) {
          keys.push({ kind: 'wheel-down' });
        }
        index += mouse[0].length;
        continue;
      }

      const fixed = FIXED_ESCAPE_SEQUENCES.find(({ sequence }) => rest.startsWith(sequence));
      if (fixed) {
        keys.push(fixed.key);
        index += fixed.sequence.length;
        continue;
      }

      if (!flush && isIncompleteEscape(rest)) {
        this.incomplete = rest;
        break;
      }

      if (rest.startsWith('\x1b')) {
        keys.push({ kind: 'escape' });
        index += 1;
        continue;
      }

      const char = Array.from(rest)[0] ?? '';
      index += char.length;
      switch (char) {
        case '\u0003':
          keys.push({ kind: 'ctrl-c' });
          break;
        case '\u0001':
          keys.push({ kind: 'ctrl-a' });
          break;
        case '\u0005':
          keys.push({ kind: 'ctrl-e' });
          break;
        case '\r':
        case '\n':
          keys.push({ kind: 'return' });
          break;
        case '\t':
          keys.push({ kind: 'tab' });
          break;
        case '\u007f':
        case '\b':
          keys.push({ kind: 'backspace' });
          break;
        default:
          if (charWidth(char) > 0) {
            keys.push({ kind: 'text', value: char });
          }
          break;
      }
    }
    return keys;
  }
}

export function parseKeys(input: string | Buffer): Key[] {
  return new KeyParser().parse(input);
}

function isIncompleteEscape(input: string): boolean {
  if (!input.startsWith('\x1b[')) {
    return false;
  }
  return FIXED_ESCAPE_SEQUENCES.some(({ sequence }) => sequence.startsWith(input))
    || /^\x1b\[<[\d;]*$/.test(input);
}
