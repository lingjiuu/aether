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

  it('renders compact completion like Codex without exposing trigger or summary text', () => {
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
    const startedState = reducer(runningState, {
      type: 'event',
      event: {
        type: 'COMPACT_STARTED',
        sessionId: 'session-1',
        turnId: 'turn-1',
        turn: 1,
        sequence: 2,
        payload: {
          payloadType: 'compact',
          text: 'manual',
          originalMessageCount: 5,
        },
      },
    });
    const finishedState = reducer(startedState, {
      type: 'event',
      event: {
        type: 'COMPACT_FINISHED',
        sessionId: 'session-1',
        turnId: 'turn-1',
        turn: 1,
        sequence: 3,
        payload: {
          payloadType: 'compact',
          text: 'raw compact summary',
          originalMessageCount: 5,
          replacementMessageCount: 2,
        },
      },
    });

    expect(startedState.turns['turn-1']?.items).toEqual([]);
    expect(finishedState.turns['turn-1']?.items).toEqual([
      expect.objectContaining({
        kind: 'CONTEXT_MESSAGE',
        status: 'COMPLETED',
        text: 'Context compacted',
      }),
    ]);
    expect(JSON.stringify(finishedState.turns['turn-1']?.items)).not.toContain('manual');
    expect(JSON.stringify(finishedState.turns['turn-1']?.items)).not.toContain('raw compact summary');
  });
});
