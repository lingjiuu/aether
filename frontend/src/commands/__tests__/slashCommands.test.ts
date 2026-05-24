import { describe, expect, it } from 'vitest';
import {
  getSlashCommandInsertText,
  getSlashCommandPlaceholder,
  getSlashCommandSuggestions,
} from '../slashCommands.js';
import { parseCommand } from '../../protocol/commands.js';

describe('parseCommand', () => {
  it('keeps normal prompts as submissions', () => {
    expect(parseCommand('hello')).toEqual({ kind: 'submit', text: 'hello' });
  });

  it('parses resume and approval commands', () => {
    expect(parseCommand('/resume')).toEqual({ kind: 'resume' });
    expect(parseCommand('/resume abc')).toEqual({ kind: 'resume', sessionId: 'abc' });
    expect(parseCommand('/rename')).toEqual({ kind: 'name', name: '' });
    expect(parseCommand('/rename custom title')).toEqual({ kind: 'name', name: 'custom title' });
    expect(parseCommand('/name custom title')).toEqual({ kind: 'name', name: 'custom title' });
    expect(parseCommand('/approve')).toEqual({ kind: 'approve' });
    expect(parseCommand('/deny')).toEqual({ kind: 'deny' });
  });

  it('does not expose continue as a user command', () => {
    expect(parseCommand('/continue')).toEqual({ kind: 'unknown', name: 'continue' });
    expect(getSlashCommandSuggestions('/cont')).toEqual([]);
  });

  it('provides insert text for slash suggestions without argument placeholders', () => {
    const [resume] = getSlashCommandSuggestions('/res');
    expect(resume?.usage).toBe('/resume');
    expect(resume ? getSlashCommandInsertText(resume) : '').toBe('/resume');

    const [help] = getSlashCommandSuggestions('/he');
    expect(help?.usage).toBe('/help');
    expect(help ? getSlashCommandInsertText(help) : '').toBe('/help');

    const [rename] = getSlashCommandSuggestions('/ren');
    expect(rename?.usage).toBe('/rename');
    expect(rename ? getSlashCommandInsertText(rename) : '').toBe('/rename ');

    const [nameAlias] = getSlashCommandSuggestions('/na');
    expect(nameAlias?.usage).toBe('/rename');
  });

  it('stops suggesting after a completed command with arguments is inserted', () => {
    expect(getSlashCommandSuggestions('/resume ')).toEqual([]);
    expect(getSlashCommandSuggestions('/resume')).toEqual([]);
    expect(getSlashCommandSuggestions('/rename ')).toEqual([]);
    expect(getSlashCommandSuggestions('/help')).toEqual([]);
  });

  it('provides inline placeholders for argument commands', () => {
    expect(getSlashCommandPlaceholder('/rename ')).toBe('name');
    expect(getSlashCommandPlaceholder('/name ')).toBe('name');
    expect(getSlashCommandPlaceholder('/rename custom title')).toBeUndefined();
    expect(getSlashCommandPlaceholder('/resume ')).toBeUndefined();
  });

  it('shows the full slash command list', () => {
    const usages = getSlashCommandSuggestions('/').map(command => command.usage);
    expect(usages).toContain('/help');
    expect(usages).toContain('/rename');
    expect(usages).not.toContain('/name');
    expect(usages).not.toContain('/approve');
    expect(usages).not.toContain('/deny');
    expect(usages).not.toContain('/continue');
  });
});
