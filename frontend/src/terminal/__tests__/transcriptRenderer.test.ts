import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import { stripAnsi, visualWidth } from '../shared/text.js';
import { activeLines, renderView, historyLines } from './renderTestHelpers.js';

describe('transcript renderer', () => {
  it('renders the session header as a Codex-style card', () => {
    const cwd = `${process.env.HOME ?? '/Users/Apple'}/code/MyProjects/test0`;
    const view = renderView({
      ...initialState,
      session: {
        ...initialState.session,
        cwd,
        model: 'ikun-openai/gpt-5.4-mini',
      },
    }, { columns: 80 });
    const text = historyLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('╭');
    expect(text).toContain('>_ Aether');
    expect(text).toContain('model:     ikun-openai/gpt-5.4-mini');
    expect(text).toContain('directory: ~/code/MyProjects/test0');
    expect(text).not.toContain('Welcome back!');
  });

  it('keeps the session header inside narrow terminal widths', () => {
    const cwd = `${process.env.HOME ?? '/Users/Apple'}/code/MyProjects/test0`;
    const view = renderView({
      ...initialState,
      session: {
        ...initialState.session,
        cwd,
        model: 'ikun-openai/gpt-5.4-mini',
      },
    }, { columns: 24 });
    const headerLines = historyLines(view).map(stripAnsi).slice(0, 6);

    expect(headerLines.some(line => line.includes('model:'))).toBe(true);
    expect(headerLines.some(line => line.includes('directory:'))).toBe(true);
    expect(headerLines.every(line => visualWidth(line) <= 23)).toBe(true);
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

    const view = renderView(state, { columns: 20 });
    const userLine = historyLines(view).find(line => stripAnsi(line).startsWith('❯ 你好啊')) ?? '';

    expect(userLine).toContain('\x1b[48;2;48;50;58m');
    expect(visualWidth(stripAnsi(userLine))).toBe(20);
  });

  it('renders assistant fenced Markdown without fence markers', () => {
    const state = assistantHistory('内容是：\n\n```python\nprint("helloworld")\n```');
    const view = renderView(state);
    const text = [...historyLines(view), ...activeLines(view)].map(stripAnsi).join('\n');

    expect(text).toContain('print("helloworld")');
    expect(text).not.toContain('```');
  });

  it('renders assistant fenced Markdown code without dim styling or synthetic padding', () => {
    const state = assistantHistory('```python\nprint("helloworld")\n```');
    const view = renderView(state);
    const codeLine = historyLines(view).find(line => stripAnsi(line).includes('print("helloworld")')) ?? '';

    expect(codeLine).toBe('● print("helloworld")');
    expect(codeLine).not.toContain('\x1b[2m');
  });

  it('does not leak streaming fence fragments', () => {
    const state = assistantHistory('内容是：\n\n```python\nprint("helloworld")\n``', 'RUNNING', 'RUNNING');
    const view = renderView(state);
    const text = [...historyLines(view), ...activeLines(view)].map(stripAnsi).join('\n');

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
