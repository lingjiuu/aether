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
      setModel: vi.fn(),
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
      setModel: vi.fn(),
    });

    expect(resumeSession).toHaveBeenCalledWith('two');
  });

  it('submits the selected model with reasoning effort', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'model',
        id: 'model-1',
        command: '/model',
        catalog: {
          current: { providerId: 'fake', modelId: 'first', reasoningEffort: 'HIGH' },
          models: [
            { providerId: 'fake', modelId: 'first', current: true },
            { providerId: 'fake', modelId: 'second' },
          ],
          reasoningEfforts: ['LOW', 'HIGH'],
        },
        selectedIndex: 1,
        reasoningIndex: 0,
      },
    });
    const setModel = vi.fn<(_: string | undefined, __: string, ___?: string) => Promise<void>>().mockResolvedValue(undefined);

    await new CommandPanelController().handleKey({ kind: 'return' }, state, {
      dispatch: vi.fn(),
      resumeSession: vi.fn(),
      setModel,
    });

    expect(setModel).toHaveBeenCalledWith('fake', 'second', 'LOW');
  });

  it('moves reasoning effort to a larger value with the right arrow', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'model',
        id: 'model-1',
        command: '/model',
        catalog: {
          current: { providerId: 'fake', modelId: 'first', reasoningEffort: 'HIGH' },
          models: [{ providerId: 'fake', modelId: 'first', current: true }],
          reasoningEfforts: ['NONE', 'XHIGH', 'HIGH', 'LOW'],
        },
        selectedIndex: 0,
        reasoningIndex: 2,
      },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'right' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelReasoningMoved', delta: 1, count: 3 }]);
  });

  it('cancels model panels with the current model output', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'model',
        id: 'model-1',
        command: '/model',
        catalog: {
          current: { providerId: 'fake', modelId: 'first', reasoningEffort: 'HIGH' },
          models: [{ providerId: 'fake', modelId: 'first', current: true }],
          reasoningEfforts: ['HIGH'],
        },
        selectedIndex: 0,
        reasoningIndex: 0,
      },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'escape' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelClosed', output: 'Kept model as fake/first' }]);
  });
});
