import { describe, expect, it, vi } from 'vitest';
import { initialState, reducer, type AppAction } from '../../state/reducer.js';
import { CommandPanelController } from '../interaction/commandPanelController.js';

describe('CommandPanelController', () => {
  it('closes help panels with the Claude-style local command output', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: { kind: 'help', id: 'help-1', command: '/help' },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'escape' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelClosed', output: 'Help dialog dismissed' }]);
  });

  it('filters resume sessions before selecting the target session', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'resume',
        id: 'resume-1',
        command: '/resume',
        sessions: [
          { sessionId: 'one', name: 'unrelated' },
          { sessionId: 'two', name: 'target project' },
        ],
        selectedIndex: 0,
        query: 'target',
      },
    });
    const resumeSession = vi.fn<(_: string) => Promise<void>>().mockResolvedValue(undefined);

    await new CommandPanelController().handleKey({ kind: 'return' }, state, {
      dispatch: vi.fn(),
      resumeSession,
    });

    expect(resumeSession).toHaveBeenCalledWith('two');
  });
});
