import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../reducer.js';

describe('reducer command panels', () => {
  it('closes command panels and records the command result in history', () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'resume',
        id: 'command-1',
        command: '/resume',
        sessions: [],
        selectedIndex: 0,
        query: '',
      },
    });

    const nextState = reducer(state, {
      type: 'commandPanelClosed',
      output: 'Resume cancelled',
    });

    expect(nextState.commandPanel).toBeUndefined();
    expect(nextState.localCommandEntries).toEqual([
      {
        id: 'command-1',
        command: '/resume',
        output: 'Resume cancelled',
        afterTurnOrderLength: 0,
      },
    ]);
  });
});
