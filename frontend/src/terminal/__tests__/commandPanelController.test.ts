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
      setPermissionMode: vi.fn(),
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
      setPermissionMode: vi.fn(),
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
        customModel: '',
      },
    });
    const setModel = vi.fn<(_: string | undefined, __: string, ___?: string) => Promise<void>>().mockResolvedValue(undefined);

    await new CommandPanelController().handleKey({ kind: 'return' }, state, {
      dispatch: vi.fn(),
      resumeSession: vi.fn(),
      setModel,
      setPermissionMode: vi.fn(),
    });

    expect(setModel).toHaveBeenCalledWith('fake', 'second', 'LOW');
  });

  it('types and submits a custom model with the current provider', async () => {
    const controller = new CommandPanelController();
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'model',
        id: 'model-1',
        command: '/model',
        catalog: {
          current: { providerId: 'fake', modelId: 'first', reasoningEffort: 'HIGH' },
          models: [{ providerId: 'fake', modelId: 'first', current: true }],
          reasoningEfforts: ['LOW', 'HIGH'],
        },
        selectedIndex: 0,
        reasoningIndex: 0,
        customModel: '',
      },
    });
    const actions: AppAction[] = [];

    await controller.handleKey({ kind: 'text', value: 'g' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelCustomModelChanged', customModel: 'g' }]);

    const action = actions[0];
    if (!action) {
      throw new Error('expected custom model action');
    }
    const nextState = reducer(state, action);
    const setModel = vi.fn<(_: string | undefined, __: string, ___?: string) => Promise<void>>().mockResolvedValue(undefined);

    await controller.handleKey({ kind: 'return' }, nextState, {
      dispatch: vi.fn(),
      resumeSession: vi.fn(),
      setModel,
      setPermissionMode: vi.fn(),
    });

    expect(setModel).toHaveBeenCalledWith('fake', 'g', 'LOW');
  });

  it('submits a slash-containing custom model under the current provider', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'model',
        id: 'model-1',
        command: '/model',
        catalog: {
          current: { providerId: 'fake', modelId: 'first', reasoningEffort: 'HIGH' },
          models: [{ providerId: 'fake', modelId: 'first', current: true }],
          reasoningEfforts: ['LOW', 'HIGH'],
        },
        selectedIndex: 1,
        reasoningIndex: 0,
        customModel: 'family/custom-model',
      },
    });
    const setModel = vi.fn<(_: string | undefined, __: string, ___?: string) => Promise<void>>().mockResolvedValue(undefined);

    await new CommandPanelController().handleKey({ kind: 'return' }, state, {
      dispatch: vi.fn(),
      resumeSession: vi.fn(),
      setModel,
      setPermissionMode: vi.fn(),
    });

    expect(setModel).toHaveBeenCalledWith('fake', 'family/custom-model', 'LOW');
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
        customModel: '',
      },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'right' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
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
        customModel: '',
      },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'escape' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelClosed', output: 'Kept model as fake/first' }]);
  });

  it('submits the selected permission mode', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'permissions',
        id: 'permissions-1',
        command: '/permissions',
        catalog: {
          current: { id: 'DEFAULT', name: 'Default', current: true },
          modes: [
            { id: 'DEFAULT', name: 'Default', current: true },
            { id: 'FULL_ACCESS', name: 'Full Access' },
          ],
        },
        selectedIndex: 1,
      },
    });
    const setPermissionMode = vi.fn<(_: string) => Promise<void>>().mockResolvedValue(undefined);

    await new CommandPanelController().handleKey({ kind: 'return' }, state, {
      dispatch: vi.fn(),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode,
    });

    expect(setPermissionMode).toHaveBeenCalledWith('FULL_ACCESS');
  });

  it('cancels permission panels with the current mode output', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'permissions',
        id: 'permissions-1',
        command: '/permissions',
        catalog: {
          current: { id: 'DEFAULT', name: 'Default', current: true },
          modes: [{ id: 'DEFAULT', name: 'Default', current: true }],
        },
        selectedIndex: 0,
      },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'escape' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelClosed', output: 'Kept permissions as Default' }]);
  });

  it('closes skills panels with Claude-style command outputs', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'skills',
        id: 'skills-1',
        command: '/skills',
        skills: [{ name: 'demo', description: 'Demo skill' }],
        selectedIndex: 0,
        query: '',
      },
    });
    const actions: AppAction[] = [];

    await new CommandPanelController().handleKey({ kind: 'return' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelClosed', output: 'No changes' }]);
  });

  it('filters skills without adding the search hotkey to the query', async () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'skills',
        id: 'skills-1',
        command: '/skills',
        skills: [{ name: 'demo', description: 'Demo skill' }],
        selectedIndex: 0,
        query: '',
      },
    });
    const actions: AppAction[] = [];
    const controller = new CommandPanelController();

    await controller.handleKey({ kind: 'text', value: '/' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
    });
    await controller.handleKey({ kind: 'text', value: 'd' }, state, {
      dispatch: action => actions.push(action),
      resumeSession: vi.fn(),
      setModel: vi.fn(),
      setPermissionMode: vi.fn(),
    });

    expect(actions).toEqual([{ type: 'commandPanelQueryChanged', query: 'd' }]);
  });
});
