import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import type { AppState } from '../../state/reducer.js';
import { renderScrollback } from '../renderScrollback.js';
import { stripAnsi } from '../text.js';

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
