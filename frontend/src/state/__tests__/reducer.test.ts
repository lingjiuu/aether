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

  it('updates skill count without surfacing watcher events as notices', () => {
    const nextState = reducer(initialState, {
      type: 'event',
      event: {
        type: 'SKILLS_CHANGED',
        sequence: 1,
        payload: { payloadType: 'text', text: 'skills changed: 3' },
      },
    });

    expect(nextState.session.availableSkillCount).toBe(3);
    expect(nextState.notices).toEqual([]);
  });

  it('tracks permission mode changes from events', () => {
    const nextState = reducer(initialState, {
      type: 'event',
      event: {
        type: 'PERMISSION_CHANGED',
        sequence: 1,
        payload: {
          payloadType: 'permissionMode',
          permissionMode: { id: 'FULL_ACCESS', name: 'Full Access', current: true },
        },
      },
    });

    expect(nextState.session.permissionMode).toBe('FULL_ACCESS');
  });

  it('tracks session running status from turn lifecycle events', () => {
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
    const abortedState = reducer(runningState, {
      type: 'event',
      event: {
        type: 'TURN_ABORTED',
        sessionId: 'session-1',
        turnId: 'turn-1',
        turn: 1,
        sequence: 2,
      },
    });

    expect(runningState.session.status).toBe('RUNNING');
    expect(abortedState.session.status).toBe('IDLE');
    expect(abortedState.turns['turn-1']?.status).toBe('ABORTED');
  });
});
