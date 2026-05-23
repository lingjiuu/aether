import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../reducer.js';
import type { UiEvent } from '../../protocol/wire.js';
import { selectTurns } from '../selectors.js';

describe('reducer', () => {
  it('builds a text item from streaming deltas and completion', () => {
    const started: UiEvent = {
      type: 'TURN_STARTED',
      turnId: 'turn-1',
      turn: 1,
    };
    const itemStarted: UiEvent = {
      type: 'ITEM_STARTED',
      turnId: 'turn-1',
      payload: {
        payloadType: 'itemStarted',
        itemKind: 'ASSISTANT_TEXT',
        itemId: 'msg-1',
        contentIndex: 0,
      },
    };
    const delta: UiEvent = {
      type: 'ASSISTANT_TEXT_DELTA',
      turnId: 'turn-1',
      payload: {
        payloadType: 'textDelta',
        itemKind: 'ASSISTANT_TEXT',
        itemId: 'msg-1',
        contentIndex: 0,
        delta: 'hello',
      },
    };
    const completed: UiEvent = {
      type: 'ITEM_COMPLETED',
      turnId: 'turn-1',
      payload: {
        payloadType: 'itemCompleted',
        item: {
          itemId: 'msg-1',
          kind: 'ASSISTANT_TEXT',
          contentIndex: 0,
          body: { bodyType: 'text', text: 'hello' },
        },
      },
    };

    const state = [started, itemStarted, delta, completed].reduce(
      (current, event) => reducer(current, { type: 'event', event }),
      initialState,
    );

    expect(selectTurns(state)).toEqual([
      {
        turnId: 'turn-1',
        commandId: undefined,
        turn: 1,
        status: 'RUNNING',
        items: [
          {
            id: 'msg-1',
            kind: 'ASSISTANT_TEXT',
            status: 'COMPLETED',
            contentIndex: 0,
            text: 'hello',
            toolCall: undefined,
            toolResult: undefined,
          },
        ],
      },
    ]);
  });

  it('attaches tool execution results to the source tool call item', () => {
    const state = [
      {
        type: 'ITEM_STARTED',
        turnId: 'turn-2',
        payload: {
          payloadType: 'itemStarted',
          itemKind: 'TOOL_CALL',
          itemId: 'tool-item',
          contentIndex: 0,
          toolCall: {
            itemId: 'tool-item',
            contentIndex: 0,
            toolCallId: 'call-1',
            toolName: 'bash',
            argumentsJson: '{"cmd":"ls"}',
          },
        },
      },
      {
        type: 'TOOL_EXECUTION_END',
        turnId: 'turn-2',
        payload: {
          payloadType: 'toolExecution',
          toolCall: {
            itemId: 'tool-item',
            contentIndex: 0,
            toolCallId: 'call-1',
            toolName: 'bash',
            argumentsJson: '{"cmd":"ls"}',
          },
          toolResult: {
            itemId: 'result-1',
            sourceItemId: 'tool-item',
            toolCallId: 'call-1',
            toolName: 'bash',
            text: 'ok',
            error: false,
            status: 'COMPLETED',
            durationMs: 12,
          },
        },
      },
    ].reduce((current, event) => reducer(current, { type: 'event', event: event as UiEvent }), initialState);

    expect(selectTurns(state)[0]?.items[0]?.toolResult?.text).toBe('ok');
    expect(selectTurns(state)[0]?.items[0]?.status).toBe('COMPLETED');
  });
});
