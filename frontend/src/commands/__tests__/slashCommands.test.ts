import { describe, expect, it } from 'vitest';
import { getSlashCommandInsertText, getSlashCommandSuggestions } from '../slashCommands.js';
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

  it('does not expose continue as a user command', () => {
    expect(parseCommand('/continue')).toEqual({ kind: 'unknown', name: 'continue' });
    expect(getSlashCommandSuggestions('/cont')).toEqual([]);
  });

  it('provides insert text for slash suggestions without argument placeholders', () => {
    const [resume] = getSlashCommandSuggestions('/res');
    expect(resume?.usage).toBe('/resume <session-id>');
    expect(resume ? getSlashCommandInsertText(resume) : '').toBe('/resume ');

    const [help] = getSlashCommandSuggestions('/he');
    expect(help?.usage).toBe('/help');
    expect(help ? getSlashCommandInsertText(help) : '').toBe('/help');
  });

  it('stops suggesting after a completed command with arguments is inserted', () => {
    expect(getSlashCommandSuggestions('/resume ')).toEqual([]);
    expect(getSlashCommandSuggestions('/help')).toEqual([]);
  });
});
