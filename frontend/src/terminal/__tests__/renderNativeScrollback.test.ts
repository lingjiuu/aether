import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import { stripAnsi } from '../shared/text.js';
import { activeLines, historyLines, longTranscriptState, renderView } from './renderTestHelpers.js';

describe('native scrollback renderer', () => {
  it('keeps stable history unbounded by terminal height', () => {
    const state = longTranscriptState(12);
    const view = renderView(state, { rows: 8 });
    const historyText = historyLines(view).map(stripAnsi).join('\n');
    const activeText = activeLines(view).map(stripAnsi).join('\n');

    expect(historyText).toContain('Welcome back!');
    expect(historyText).toContain('message-0');
    expect(historyText).toContain('message-11');
    expect(activeText).toContain('❯ ');
  });

  it('commits completed parallel tools while keeping the running tail active', () => {
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
    const historyText = historyLines(view).map(stripAnsi).join('\n');
    const activeText = activeLines(view).map(stripAnsi).join('\n');

    expect(historyText).toContain('❯ 你每一个都调用一遍给我看看');
    expect(historyText).toContain('● ls(dir-7)');
    expect(historyText).not.toContain('● Bash(sleep 10)');
    expect(activeText).toContain('● Bash(sleep 10)');
    expect(activeText).toContain('⎿  Running...');
    expect(activeText).toContain('❯ ');
    expect(activeText).toContain('esc to interrupt');
  });
});
