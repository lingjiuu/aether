import { describe, expect, it } from 'vitest';
import { parseCommand } from '../../protocol/commands.js';

describe('parseCommand', () => {
  it('keeps normal prompts as submissions', () => {
    expect(parseCommand('hello')).toEqual({ kind: 'submit', text: 'hello' });
  });

  it('parses resume and approval commands', () => {
    expect(parseCommand('/resume abc')).toEqual({ kind: 'resume', sessionId: 'abc' });
    expect(parseCommand('/approve')).toEqual({ kind: 'approve' });
    expect(parseCommand('/deny')).toEqual({ kind: 'deny' });
  });
});
