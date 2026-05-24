import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import { stripAnsi } from '../shared/text.js';
import { longTranscriptState, renderView } from './renderTestHelpers.js';

describe('render viewport', () => {
  it('sticks the transcript viewport to the bottom when no scroll top is set', () => {
    const state = longTranscriptState(12);
    const view = renderView(state, { rows: 10 });
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
    const view = renderView(state, { rows: 10, transcriptScrollTop: 0 });
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
    const view = renderView(state, { rows: 10, transcriptScrollTop: 999 });

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
                status: 'COMPLETED' as const,
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

    const view = renderView(state, { columns: 100, rows: 12 });
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
