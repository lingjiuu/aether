import { describe, expect, it } from 'vitest';
import { renderText, toolHistoryState } from './renderTestHelpers.js';

describe('tool renderers', () => {
  it('renders read results as a Claude-style summary instead of raw file text', () => {
    const state = toolHistoryState({
      id: 'read-1',
      toolCall: {
        toolName: 'read',
        arguments: { file_path: '滕王阁序.md' },
        argumentsJson: JSON.stringify({ file_path: '滕王阁序.md' }),
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

  it('renders grep results as file counts by default', () => {
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
        text: 'Found 2 files\na.txt\nb.txt',
        details: { kind: 'grep', pattern: 'hello', mode: 'files_with_matches', numFiles: 2 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Search(pattern: "hello")');
    expect(text).toContain('⎿  Found 2 files');
  });

  it('renders glob results as a file count', () => {
    const state = toolHistoryState({
      id: 'glob-1',
      toolCall: {
        toolName: 'glob',
        arguments: { pattern: '*.md' },
        argumentsJson: JSON.stringify({ pattern: '*.md' }),
        displayName: 'glob',
        displaySummary: '*.md',
      },
      toolResult: {
        toolName: 'glob',
        text: 'helloworld.md\n滕王阁序.md',
        details: { kind: 'glob', pattern: '*.md', numFiles: 2 },
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
        arguments: { file_path: 'foo.txt' },
        argumentsJson: JSON.stringify({ file_path: 'foo.txt' }),
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

  it('renders failed write results as errors', () => {
    const state = toolHistoryState({
      id: 'write-error-1',
      toolCall: {
        toolName: 'write',
        arguments: { file_path: 'foo.txt' },
        argumentsJson: JSON.stringify({ file_path: 'foo.txt' }),
        displayName: 'write',
        displaySummary: 'foo.txt',
      },
      toolResult: {
        toolName: 'write',
        text: 'write failed: File has not been read yet',
        error: true,
        details: null,
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Write(foo.txt)');
    expect(text).toContain('write failed: File has not been read yet');
    expect(text).not.toContain('Wrote file');
  });

  it('renders edit summaries and diffs', () => {
    const state = toolHistoryState({
      id: 'edit-1',
      toolCall: {
        toolName: 'edit',
        arguments: { file_path: 'foo.txt' },
        argumentsJson: JSON.stringify({ file_path: 'foo.txt' }),
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

  it('shortens absolute file paths under the session cwd', () => {
    const cwd = '/Users/Apple/code/MyProjects/test0';
    const state = toolHistoryState({
      id: 'read-absolute-1',
      cwd,
      toolCall: {
        toolName: 'read',
        arguments: { file_path: `${cwd}/notes/todo.md` },
        argumentsJson: JSON.stringify({ file_path: `${cwd}/notes/todo.md` }),
        displayName: 'read',
        displaySummary: `${cwd}/notes/todo.md`,
      },
      toolResult: {
        toolName: 'read',
        text: 'hello',
        details: { kind: 'read', path: `${cwd}/notes/todo.md`, returnedLines: 1 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● Read(notes/todo.md)');
    expect(text).not.toContain(`Read(${cwd}/notes/todo.md)`);
  });

  it('compacts large edit diffs', () => {
    const diffText = [
      ...Array.from({ length: 50 }, (_, index) => `-Row ${String(index + 1).padStart(2, '0')}: placeholder`),
      ...Array.from({ length: 50 }, (_, index) => `+Row ${String(index + 1).padStart(2, '0')}: updated`),
    ].join('\n');
    const state = toolHistoryState({
      id: 'edit-large-1',
      toolCall: {
        toolName: 'edit',
        arguments: { file_path: 'large.txt' },
        argumentsJson: JSON.stringify({ file_path: 'large.txt' }),
        displayName: 'edit',
        displaySummary: 'large.txt',
      },
      toolResult: {
        toolName: 'edit',
        text: 'Successfully replaced 1 block',
        details: { kind: 'edit', path: 'large.txt', editCount: 1, diffText },
      },
    });

    const text = renderText(state, 120);
    expect(text).toContain('50 added, 50 removed');
    expect(text).toContain('... 90 diff lines hidden');
    expect(text).toContain('-Row 01: placeholder');
    expect(text).toContain('+Row 50: updated');
    expect(text).not.toContain('-Row 20: placeholder');
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

  it('renders PowerShell with shell presenter', () => {
    const state = toolHistoryState({
      id: 'powershell-1',
      toolCall: {
        toolName: 'PowerShell',
        arguments: { command: 'Get-ChildItem' },
        argumentsJson: JSON.stringify({ command: 'Get-ChildItem' }),
        displayName: 'PowerShell',
        displaySummary: 'Get-ChildItem',
      },
      toolResult: {
        toolName: 'PowerShell',
        text: '',
        durationMs: 50,
        details: { kind: 'powershell', command: 'Get-ChildItem', stdout: '', stderr: '', durationMs: 50 },
      },
    });

    const text = renderText(state);
    expect(text).toContain('● PowerShell(Get-ChildItem)');
    expect(text).toContain('⎿  Done 50ms');
  });

  it('renders multiline bash commands as a single compact summary', () => {
    const command = [
      "wc -c big_doc.txt && python3 - <<'PY'",
      'from pathlib import Path',
      "p = Path('big_doc.txt')",
      "print('lines', len(p.read_text().splitlines()))",
      'PY',
    ].join('\n');
    const state = toolHistoryState({
      id: 'bash-multiline-1',
      toolCall: {
        toolName: 'bash',
        arguments: { command },
        argumentsJson: JSON.stringify({ command }),
        displayName: 'bash',
        displaySummary: command,
      },
      toolResult: {
        toolName: 'bash',
        text: '7472 big_doc.txt\nlines 253',
        details: {
          kind: 'bash',
          command,
          stdout: '7472 big_doc.txt\nlines 253',
          stderr: '',
        },
      },
    });

    const text = renderText(state, 180);
    expect(text).toContain("● Bash(wc -c big_doc.txt && python3 - <<'PY' from pathlib import Path");
    expect(text).not.toContain("● Bash(wc -c big_doc.txt && python3 - <<'PY'\nfrom pathlib import Path");
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
