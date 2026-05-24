import { describe, expect, it } from 'vitest';
import { parseKeys } from '../TerminalApp.js';

describe('parseKeys', () => {
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
});
