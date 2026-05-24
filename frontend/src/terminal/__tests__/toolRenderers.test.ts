import { describe, expect, it } from 'vitest';
import { renderText, toolHistoryState } from './renderTestHelpers.js';

describe('tool renderers', () => {
  it('renders read results as a Claude-style summary instead of raw file text', () => {
    const state = toolHistoryState({
      id: 'read-1',
      toolCall: {
        toolName: 'read',
        arguments: { path: '滕王阁序.md' },
        argumentsJson: JSON.stringify({ path: '滕王阁序.md' }),
        displayName: 'read',
        displaySummary: '滕王阁序.md',
      },
      toolResult: {
        toolName: 'read',
        text: '披绣闼，俯雕甍。\n山原旷其盈视。',
        details: {
          kind: 'read',
          path: '滕王阁序.md',
          fileType: 'text',
          returnedLines: 30,
          totalLines: 30,
        },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Read(滕王阁序.md)');
    expect(text).toContain('⎿  Read 30 lines');
    expect(text).not.toContain('披绣闼');
  });

  it('renders ls results as an entry count', () => {
    const state = toolHistoryState({
      id: 'ls-1',
      toolCall: {
        toolName: 'ls',
        arguments: { path: '.' },
        argumentsJson: JSON.stringify({ path: '.' }),
        displayName: 'ls',
        displaySummary: '.',
      },
      toolResult: {
        toolName: 'ls',
        text: '.claude/\nhello.txt\nhelloworld.md\n滕王阁序.md\n.DS_Store',
        details: { kind: 'ls', path: '.', entryCount: 5 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● ls(.)');
    expect(text).toContain('⎿  Listed 5 entries');
  });

  it('renders grep results as match and file counts', () => {
    const state = toolHistoryState({
      id: 'grep-1',
      toolCall: {
        toolName: 'grep',
        arguments: { pattern: 'hello' },
        argumentsJson: JSON.stringify({ pattern: 'hello' }),
        displayName: 'grep',
        displaySummary: 'hello',
      },
      toolResult: {
        toolName: 'grep',
        text: 'a.txt:1:hello\nb.txt:2:hello',
        details: { kind: 'grep', pattern: 'hello', matchCount: 2, fileCount: 2 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Search(pattern: "hello")');
    expect(text).toContain('⎿  Found 2 matches across 2 files');
  });

  it('renders find results as a file count', () => {
    const state = toolHistoryState({
      id: 'find-1',
      toolCall: {
        toolName: 'find',
        arguments: { pattern: '*.md' },
        argumentsJson: JSON.stringify({ pattern: '*.md' }),
        displayName: 'find',
        displaySummary: '*.md',
      },
      toolResult: {
        toolName: 'find',
        text: 'helloworld.md\n滕王阁序.md',
        details: { kind: 'find', pattern: '*.md', resultCount: 2 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Search(pattern: "*.md")');
    expect(text).toContain('⎿  Found 2 files');
  });

  it('renders write results from structured line counts', () => {
    const state = toolHistoryState({
      id: 'write-1',
      toolCall: {
        toolName: 'write',
        arguments: { path: 'foo.txt' },
        argumentsJson: JSON.stringify({ path: 'foo.txt' }),
        displayName: 'write',
        displaySummary: 'foo.txt',
      },
      toolResult: {
        toolName: 'write',
        text: 'Successfully wrote foo.txt',
        details: { kind: 'write', path: 'foo.txt', lineCount: 6, chars: 42 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Write(foo.txt)');
    expect(text).toContain('⎿  Wrote 6 lines to foo.txt');
  });

  it('renders edit summaries and diffs', () => {
    const state = toolHistoryState({
      id: 'edit-1',
      toolCall: {
        toolName: 'edit',
        arguments: { path: 'foo.txt' },
        argumentsJson: JSON.stringify({ path: 'foo.txt' }),
        displayName: 'edit',
        displaySummary: 'foo.txt',
      },
      toolResult: {
        toolName: 'edit',
        text: 'Successfully replaced 1 occurrence',
        details: {
          kind: 'edit',
          path: 'foo.txt',
          editCount: 1,
          diffText: '- old\n+ new',
        },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Update(foo.txt)');
    expect(text).toContain('⎿  Updated foo.txt with 1 edit');
    expect(text).toContain('- old');
    expect(text).toContain('+ new');
  });

  it('renders bash success without output as done', () => {
    const state = toolHistoryState({
      id: 'bash-1',
      toolCall: {
        toolName: 'bash',
        arguments: { command: 'mkdir tmp' },
        argumentsJson: JSON.stringify({ command: 'mkdir tmp' }),
        displayName: 'bash',
        displaySummary: 'mkdir tmp',
      },
      toolResult: {
        toolName: 'bash',
        text: '',
        durationMs: 50,
        details: { kind: 'bash', command: 'mkdir tmp', stdout: '', stderr: '', durationMs: 50 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Bash(mkdir tmp)');
    expect(text).toContain('⎿  Done 50ms');
  });

  it('renders bash stdout and stderr from structured details', () => {
    const state = toolHistoryState({
      id: 'bash-2',
      toolCall: {
        toolName: 'bash',
        arguments: { command: 'npm test' },
        argumentsJson: JSON.stringify({ command: 'npm test' }),
        displayName: 'bash',
        displaySummary: 'npm test',
      },
      toolResult: {
        toolName: 'bash',
        text: '{"stdout":"ok","stderr":"warn"}',
        details: {
          kind: 'bash',
          command: 'npm test',
          stdout: 'ok',
          stderr: 'warn',
          stdoutLineCount: 1,
          stderrLineCount: 1,
        },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Bash(npm test)');
    expect(text).toContain('⎿  ok');
    expect(text).toContain('warn');
    expect(text).not.toContain('"stdout"');
  });
});
