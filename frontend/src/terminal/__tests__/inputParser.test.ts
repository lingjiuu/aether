import { describe, expect, it } from 'vitest';
import { KeyParser, parseKeys } from '../input/inputParser.js';

describe('inputParser', () => {
  it('parses transcript scrolling keys', () => {
    expect(parseKeys('\x1b[5~')).toEqual([{ kind: 'page-up' }]);
    expect(parseKeys('\x1b[6~')).toEqual([{ kind: 'page-down' }]);
    expect(parseKeys('\x1b[H')).toEqual([{ kind: 'home' }]);
    expect(parseKeys('\x1b[F')).toEqual([{ kind: 'end' }]);
  });

  it('parses SGR mouse wheel events', () => {
    expect(parseKeys('\x1b[<64;12;4M')).toEqual([{ kind: 'wheel-up' }]);
    expect(parseKeys('\x1b[<65;12;4M')).toEqual([{ kind: 'wheel-down' }]);
    expect(parseKeys('\x1b[<68;12;4M')).toEqual([{ kind: 'wheel-up' }]);
    expect(parseKeys('\x1b[<69;12;4M')).toEqual([{ kind: 'wheel-down' }]);
  });

  it('preserves incomplete escape sequences across chunks', () => {
    const parser = new KeyParser();

    expect(parser.parse('\x1b[5')).toEqual([]);
    expect(parser.parse('~x')).toEqual([{ kind: 'page-up' }, { kind: 'text', value: 'x' }]);
  });
});
