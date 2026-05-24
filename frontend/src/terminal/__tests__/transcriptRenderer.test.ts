import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import { stripAnsi, visualWidth } from '../shared/text.js';
import { bottomLines, renderView, transcriptLines } from './renderTestHelpers.js';

describe('transcript renderer', () => {
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

    const view = renderView(state, { columns: 20 });
    const userLine = transcriptLines(view).find(line => stripAnsi(line).startsWith('❯ 你好啊')) ?? '';

    expect(userLine).toContain('\x1b[48;2;48;50;58m');
    expect(visualWidth(stripAnsi(userLine))).toBe(20);
  });

  it('renders assistant fenced Markdown without fence markers', () => {
    const state = assistantHistory('内容是：\n\n```python\nprint("helloworld")\n```');
    const view = renderView(state);
    const text = [...transcriptLines(view), ...bottomLines(view)].map(stripAnsi).join('\n');

    expect(text).toContain('print("helloworld")');
    expect(text).not.toContain('```');
  });

  it('renders assistant fenced Markdown code without dim styling or synthetic padding', () => {
    const state = assistantHistory('```python\nprint("helloworld")\n```');
    const view = renderView(state);
    const codeLine = transcriptLines(view).find(line => stripAnsi(line).includes('print("helloworld")')) ?? '';

    expect(codeLine).toBe('● print("helloworld")');
    expect(codeLine).not.toContain('\x1b[2m');
  });

  it('does not leak streaming fence fragments', () => {
    const state = assistantHistory('内容是：\n\n```python\nprint("helloworld")\n``', 'RUNNING', 'RUNNING');
    const view = renderView(state);
    const text = [...transcriptLines(view), ...bottomLines(view)].map(stripAnsi).join('\n');

    expect(text).toContain('print("helloworld")');
    expect(text).not.toContain('``');
  });
});

function assistantHistory(
  text: string,
  turnStatus: 'COMPLETED' | 'RUNNING' = 'COMPLETED',
  itemStatus: 'COMPLETED' | 'RUNNING' = 'COMPLETED',
) {
  return reducer(initialState, {
    type: 'history',
    history: {
      sessionId: 'session-1',
      turns: [
        {
          turnId: 'turn-1',
          status: turnStatus,
          items: [
            {
              id: 'assistant-1',
              kind: 'ASSISTANT_TEXT',
              status: itemStatus,
              text,
            },
          ],
        },
      ],
    },
  });
}
