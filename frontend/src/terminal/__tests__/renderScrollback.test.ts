import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import type { AppState, LocalCommandEntry } from '../../state/reducer.js';
import { renderScrollback } from '../renderScrollback.js';
import { stripAnsi, visualWidth } from '../text.js';

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

    const stableText = view.sections.flatMap(section => section.lines).map(stripAnsi).join('\n');
    expect(view.cursor).toEqual({ x: 2, y: 1 });
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

    expect(view.cursor).toEqual({ x: 6, y: 1 });
    expect(view.liveLines.map(stripAnsi).join('\n')).toContain('❯ 你好');
  });

  it('renders argument placeholders without moving the real cursor past them', () => {
    const state = reducer(initialState, { type: 'composerChanged', value: '/rename ' });

    const view = renderScrollback({
      state,
      columns: 80,
      rows: 24,
      composerCursorOffset: '/rename '.length,
    });

    expect(view.cursor).toEqual({ x: 10, y: 1 });
    expect(view.liveLines.map(stripAnsi).join('\n')).toContain('❯ /rename [name]');
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

    const text = view.liveLines.map(stripAnsi).join('\n');
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

    const userLine = view.sections.flatMap(section => section.lines).find(line => stripAnsi(line).startsWith('❯ 你好啊')) ?? '';
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

    const commandLine = view.sections.flatMap(section => section.lines).find(line => stripAnsi(line).startsWith('❯ /help')) ?? '';
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

    const text = [...view.sections.flatMap(section => section.lines), ...view.liveLines].map(stripAnsi).join('\n');
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

    const renderedLines = view.sections.flatMap(section => section.lines);
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

    const text = [...view.sections.flatMap(section => section.lines), ...view.liveLines].map(stripAnsi).join('\n');
    expect(text).toContain('print("helloworld")');
    expect(text).not.toContain('``');
  });
});
