import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import type { AppState, LocalCommandEntry } from '../../state/reducer.js';
import { renderScrollback } from '../renderScrollback.js';
import { stripAnsi, visualWidth } from '../text.js';

function renderText(state: AppState, columns = 100): string {
  const view = renderScrollback({
    state,
    columns,
    rows: 40,
    composerCursorOffset: 0,
  });

  return [...view.transcriptLines, ...view.bottomLines].map(stripAnsi).join('\n');
}

describe('renderScrollback', () => {
  it('keeps command-panel cancellation as stable scrollback history', () => {
    const opened = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'resume',
        id: 'resume-1',
        command: '/resume',
        sessions: [],
        selectedIndex: 0,
        query: '',
      },
    });
    const closed = reducer(opened, { type: 'commandPanelClosed', output: 'Resume cancelled' });

    const view = renderScrollback({
      state: closed,
      columns: 80,
      rows: 24,
      composerCursorOffset: 0,
    });

    const stableText = view.transcriptLines.map(stripAnsi).join('\n');
    expect(view.cursor).toEqual({ x: 2, y: view.transcriptLines.length + 1 });
    expect(stableText).toContain('❯ /resume');
    expect(stableText).toContain('⎿  Resume cancelled');
  });

  it('uses visual width for committed Chinese input', () => {
    const state = reducer(initialState, { type: 'composerChanged', value: '你好' });

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: '你好'.length,
    });

    expect(view.cursor).toEqual({ x: 6, y: view.transcriptLines.length + 1 });
    expect(view.bottomLines.map(stripAnsi).join('\n')).toContain('❯ 你好');
  });

  it('renders argument placeholders without moving the real cursor past them', () => {
    const state = reducer(initialState, { type: 'composerChanged', value: '/rename ' });

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: '/rename '.length,
    });

    expect(view.cursor).toEqual({ x: 10, y: view.transcriptLines.length + 1 });
    expect(view.bottomLines.map(stripAnsi).join('\n')).toContain('❯ /rename [name]');
  });

  it('renders approval requests as a focused choice panel', () => {
    const state: AppState = {
      ...initialState,
      pendingApproval: {
        request: {
          approvalId: 'approval-1',
          toolName: 'write',
          riskLevel: 'medium',
          arguments: { path: '滕王阁序.md' },
          reason: 'Create a new file',
        },
      },
    };

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: 0,
      approvalSelectedIndex: 1,
    });

    const text = view.bottomLines.map(stripAnsi).join('\n');
    expect(text).toContain('Approval required: write');
    expect(text).toContain('  Approve');
    expect(text).toContain('› Deny');
    expect(text).not.toContain('/approve');
    expect(text).not.toContain('/deny');
  });

  it('renders committed user messages with a full-row background', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'COMPLETED',
            items: [
              {
                id: 'user-1',
                kind: 'USER_MESSAGE',
                status: 'COMPLETED',
                text: '你好啊',
              },
            ],
          },
        ],
      },
    });

    const view = renderScrollback({
      state,
      columns: 20,
      rows: 24,
      composerCursorOffset: 0,
    });

    const userLine = view.transcriptLines.find(line => stripAnsi(line).startsWith('❯ 你好啊')) ?? '';
    expect(userLine).toContain('\x1b[48;2;48;50;58m');
    expect(visualWidth(stripAnsi(userLine))).toBe(20);
  });

  it('keeps local command backgrounds tight to the command text', () => {
    const localEntry: LocalCommandEntry = {
      id: 'local-1',
      command: '/help',
      output: 'Help dialog dismissed',
      afterTurnOrderLength: 0,
    };
    const view = renderScrollback({
      state: { ...initialState, localCommandEntries: [localEntry] },
      columns: 20,
      rows: 24,
      composerCursorOffset: 0,
    });

    const commandLine = view.transcriptLines.find(line => stripAnsi(line).startsWith('❯ /help')) ?? '';
    expect(commandLine).toContain('\x1b[48;2;48;50;58m');
    expect(stripAnsi(commandLine)).toBe('❯ /help');
  });

  it('renders assistant fenced Markdown without fence markers', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'COMPLETED',
            items: [
              {
                id: 'assistant-1',
                kind: 'ASSISTANT_TEXT',
                status: 'COMPLETED',
                text: '内容是：\n\n```python\nprint("helloworld")\n```',
              },
            ],
          },
        ],
      },
    });

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: 0,
    });

    const text = [...view.transcriptLines, ...view.bottomLines].map(stripAnsi).join('\n');
    expect(text).toContain('print("helloworld")');
    expect(text).not.toContain('```');
  });

  it('renders assistant fenced Markdown code without dim styling or synthetic padding', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'COMPLETED',
            items: [
              {
                id: 'assistant-1',
                kind: 'ASSISTANT_TEXT',
                status: 'COMPLETED',
                text: '```python\nprint("helloworld")\n```',
              },
            ],
          },
        ],
      },
    });

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: 0,
    });

    const renderedLines = view.transcriptLines;
    const codeLine = renderedLines.find(line => stripAnsi(line).includes('print("helloworld")')) ?? '';
    expect(codeLine).toBe('● print("helloworld")');
    expect(codeLine).not.toContain('\x1b[2m');
  });

  it('does not leak streaming fence fragments', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'RUNNING',
            items: [
              {
                id: 'assistant-1',
                kind: 'ASSISTANT_TEXT',
                status: 'RUNNING',
                text: '内容是：\n\n```python\nprint("helloworld")\n``',
              },
            ],
          },
        ],
      },
    });

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: 0,
    });

    const text = [...view.transcriptLines, ...view.bottomLines].map(stripAnsi).join('\n');
    expect(text).toContain('print("helloworld")');
    expect(text).not.toContain('``');
  });

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

  it('sticks the transcript viewport to the bottom when no scroll top is set', () => {
    const state = longTranscriptState(12);

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 10,
      composerCursorOffset: 0,
    });
    const transcriptText = view.transcriptLines.map(stripAnsi).join('\n');
    const bottomText = view.bottomLines.map(stripAnsi).join('\n');

    expect(view.scroll.isAtBottom).toBe(true);
    expect(view.scroll.maxScrollTop).toBeGreaterThan(0);
    expect(transcriptText).toContain('message-11');
    expect(transcriptText).not.toContain('Welcome back!');
    expect(bottomText).toContain('❯ ');
  });

  it('uses the app-managed transcript scroll top above a pinned bottom pane', () => {
    const state = longTranscriptState(12);

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 10,
      composerCursorOffset: 0,
      transcriptScrollTop: 0,
    });
    const transcriptText = view.transcriptLines.map(stripAnsi).join('\n');
    const bottomText = view.bottomLines.map(stripAnsi).join('\n');

    expect(view.scroll.scrollTop).toBe(0);
    expect(view.scroll.isAtBottom).toBe(false);
    expect(transcriptText).toContain('Welcome back!');
    expect(transcriptText).not.toContain('message-11');
    expect(bottomText).toContain('❯ ');
  });

  it('clamps transcript scroll top to the available transcript range', () => {
    const state = longTranscriptState(12);

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 10,
      composerCursorOffset: 0,
      transcriptScrollTop: 999,
    });

    expect(view.scroll.scrollTop).toBe(view.scroll.maxScrollTop);
    expect(view.scroll.isAtBottom).toBe(true);
  });

  it('keeps a running parallel tool turn in the transcript viewport and pins the bottom pane', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'COMPLETED',
            items: [
              {
                id: 'assistant-previous',
                kind: 'ASSISTANT_TEXT',
                status: 'COMPLETED',
                text: '上一轮回复',
              },
            ],
          },
          {
            turnId: 'turn-2',
            status: 'RUNNING',
            items: [
              {
                id: 'user-2',
                kind: 'USER_MESSAGE',
                status: 'COMPLETED',
                text: '你每一个都调用一遍给我看看',
              },
              ...Array.from({ length: 8 }, (_, index) => ({
                id: `tool-${index}`,
                kind: 'TOOL_CALL' as const,
                status: 'COMPLETED',
                toolCall: {
                  toolName: 'ls',
                  arguments: { path: `dir-${index}` },
                  argumentsJson: JSON.stringify({ path: `dir-${index}` }),
                  displayName: 'ls',
                  displaySummary: `dir-${index}`,
                },
                toolResult: {
                  toolName: 'ls',
                  text: '',
                  details: { kind: 'ls', path: `dir-${index}`, entryCount: index + 1 },
                },
              })),
              {
                id: 'tool-running',
                kind: 'TOOL_CALL',
                status: 'RUNNING',
                toolCall: {
                  toolName: 'bash',
                  arguments: { command: 'sleep 10' },
                  argumentsJson: JSON.stringify({ command: 'sleep 10' }),
                  displayName: 'bash',
                  displaySummary: 'sleep 10',
                },
              },
            ],
          },
        ],
      },
    });

    const view = renderScrollback({
      state,
      columns: 100,
      rows: 12,
      composerCursorOffset: 0,
    });
    const transcriptText = view.transcriptLines.map(stripAnsi).join('\n');
    const bottomText = view.bottomLines.map(stripAnsi).join('\n');

    expect(view.transcriptLines.length + view.bottomLines.length).toBeLessThanOrEqual(12);
    expect(bottomText).toContain('❯ ');
    expect(bottomText).toContain('esc to interrupt');
    expect(transcriptText).toContain('● ls(dir-7)');
    expect(transcriptText).toContain('● Bash(sleep 10)');
    expect(transcriptText).toContain('⎿  Running...');
    expect(bottomText).not.toContain('● Bash(sleep 10)');
  });
});

type ToolHistory = {
  id: string;
  toolCall: NonNullable<AppState['turns'][string]['items'][number]['toolCall']>;
  toolResult: NonNullable<AppState['turns'][string]['items'][number]['toolResult']>;
};

function longTranscriptState(count: number): AppState {
  return reducer(initialState, {
    type: 'history',
    history: {
      sessionId: 'session-1',
      turns: [
        {
          turnId: 'turn-1',
          status: 'COMPLETED',
          items: Array.from({ length: count }, (_, index) => ({
            id: `assistant-${index}`,
            kind: 'ASSISTANT_TEXT' as const,
            status: 'COMPLETED' as const,
            text: `message-${index}`,
          })),
        },
      ],
    },
  });
}

function toolHistoryState({ id, toolCall, toolResult }: ToolHistory): AppState {
  return reducer(initialState, {
    type: 'history',
    history: {
      sessionId: 'session-1',
      turns: [
        {
          turnId: 'turn-1',
          status: 'COMPLETED',
          items: [
            {
              id,
              kind: 'TOOL_CALL',
              status: 'COMPLETED',
              toolCall,
              toolResult,
            },
          ],
        },
      ],
    },
  });
}
