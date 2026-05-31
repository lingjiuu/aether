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
        appVersion: '0.1.0',
        cwd,
        model: 'ikun-openai/gpt-5.4-mini',
      },
    }, { columns: 80 });
    const text = historyLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('╭');
    expect(text).toContain('>_ Aether (v0.1.0) ☆˶> x <˶.⁺');
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

    expect(userLine).toContain('\x1b[48;2;67;70;86m');
    expect(userLine).not.toContain('\x1b[1m');
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

  it('renders stream retry as the active turn status', () => {
    const runningState = reducer(initialState, {
      type: 'event',
      event: {
        type: 'TURN_STARTED',
        sessionId: 'session-1',
        turnId: 'turn-1',
        turn: 1,
        sequence: 1,
      },
    });
    const retryState = reducer(runningState, {
      type: 'event',
      event: {
        type: 'STREAM_RETRY',
        sessionId: 'session-1',
        turnId: 'turn-1',
        turn: 1,
        sequence: 2,
        payload: { payloadType: 'text', text: 'Reconnecting... 1/3' },
      },
    });
    const retryView = renderView(retryState);
    const retryText = activeLines(retryView).map(stripAnsi).join('\n');

    expect(retryText).toContain('✻ Reconnecting... 1/3');

    const resumedState = reducer(retryState, {
      type: 'event',
      event: {
        type: 'ITEM_STARTED',
        sessionId: 'session-1',
        turnId: 'turn-1',
        turn: 1,
        sequence: 3,
        payload: {
          payloadType: 'itemStarted',
          itemKind: 'ASSISTANT_TEXT',
          itemId: 'assistant-1',
          contentIndex: 0,
        },
      },
    });
    const resumedText = activeLines(renderView(resumedState)).map(stripAnsi).join('\n');

    expect(resumedText).toContain('✻ Working...');
    expect(resumedText).not.toContain('Reconnecting... 1/3');
  });

  it('does not render interrupted-turn context guidance', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'ABORTED',
            items: [
              {
                id: 'context-1',
                kind: 'CONTEXT_MESSAGE',
                status: 'COMPLETED',
                text: '<turn_aborted>\nThe previous turn was interrupted by the user.\n</turn_aborted>',
              },
            ],
          },
        ],
      },
    });
    const view = renderView(state);
    const text = [...historyLines(view), ...activeLines(view)].map(stripAnsi).join('\n');

    expect(text).not.toContain('<turn_aborted>');
    expect(text).not.toContain('The previous turn was interrupted by the user.');
    expect(text).toContain('✻ Worked interrupted');
  });

  it('does not render environment context guidance', () => {
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
                id: 'context-1',
                kind: 'CONTEXT_MESSAGE',
                status: 'COMPLETED',
                text: '<environment_context>\n  <cwd>/tmp/aether</cwd>\n</environment_context>',
              },
            ],
          },
        ],
      },
    });
    const view = renderView(state);
    const text = [...historyLines(view), ...activeLines(view)].map(stripAnsi).join('\n');

    expect(text).not.toContain('<environment_context>');
    expect(text).not.toContain('/tmp/aether');
  });

  it('does not render hidden prompt context guidance', () => {
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
                id: 'context-1',
                kind: 'CONTEXT_MESSAGE',
                status: 'COMPLETED',
                text: '<additional_instructions>\nPrefer small diffs.\n</additional_instructions>',
              },
              {
                id: 'context-2',
                kind: 'CONTEXT_MESSAGE',
                status: 'COMPLETED',
                text: '<tool_context>\nAvailable tools:\n- read: Read files\n</tool_context>',
              },
              {
                id: 'context-3',
                kind: 'CONTEXT_MESSAGE',
                status: 'COMPLETED',
                text: '# AGENTS.md instructions for /tmp/aether\n\n<INSTRUCTIONS>\nProject rules.\n</INSTRUCTIONS>',
              },
              {
                id: 'context-4',
                kind: 'CONTEXT_MESSAGE',
                status: 'COMPLETED',
                text: '<available_skills>\n## Skills\n</available_skills>',
              },
            ],
          },
        ],
      },
    });
    const view = renderView(state);
    const text = [...historyLines(view), ...activeLines(view)].map(stripAnsi).join('\n');

    expect(text).not.toContain('Prefer small diffs');
    expect(text).not.toContain('Available tools');
    expect(text).not.toContain('Project rules');
    expect(text).not.toContain('available_skills');
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
