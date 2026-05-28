import { filterSessions } from '../../domain/sessionSummary.js';
import { filterSkills } from '../../domain/skills.js';
import { selectableReasoningEfforts } from '../../domain/modelCatalog.js';
import type { AppAction, AppState, CommandPanel } from '../../state/reducer.js';
import type { Key } from '../input/inputParser.js';
import { clampIndex } from '../shared/terminalMath.js';

type CommandPanelCallbacks = {
  dispatch: (action: AppAction) => void;
  resumeSession: (sessionId: string) => Promise<void>;
  setModel: (providerId: string | undefined, modelId: string, reasoningEffort?: string) => Promise<void>;
  setPermissionMode: (permissionMode: string) => Promise<void>;
};

export class CommandPanelController {
  async handleKey(key: Key, state: AppState, callbacks: CommandPanelCallbacks): Promise<boolean> {
    const panel = state.commandPanel;
    if (!panel) {
      return false;
    }

    if (key.kind === 'escape') {
      callbacks.dispatch({
        type: 'commandPanelClosed',
        output: commandPanelCancelOutput(panel),
      });
      return true;
    }

    if (panel.kind === 'model') {
      return this.handleModelKey(key, panel, callbacks);
    }

    if (panel.kind === 'skills') {
      return this.handleSkillsKey(key, panel, callbacks);
    }

    if (panel.kind === 'permissions') {
      return this.handlePermissionsKey(key, panel, callbacks);
    }

    if (panel.kind !== 'resume') {
      return true;
    }

    const sessions = filterSessions(panel.sessions, panel.query);
    switch (key.kind) {
      case 'up':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: -1, count: sessions.length });
        return true;
      case 'down':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: 1, count: sessions.length });
        return true;
      case 'backspace':
        callbacks.dispatch({ type: 'commandPanelQueryChanged', query: panel.query.slice(0, -1) });
        return true;
      case 'return': {
        const selected = sessions[clampIndex(panel.selectedIndex, sessions.length)];
        if (selected?.sessionId) {
          await callbacks.resumeSession(selected.sessionId);
        }
        return true;
      }
      case 'text':
        callbacks.dispatch({ type: 'commandPanelQueryChanged', query: `${panel.query}${key.value}` });
        return true;
      default:
        return true;
    }
  }

  private async handleModelKey(key: Key, panel: Extract<CommandPanel, { kind: 'model' }>, callbacks: CommandPanelCallbacks): Promise<boolean> {
    const models = panel.catalog.models ?? [];
    const efforts = selectableReasoningEfforts(panel.catalog.reasoningEfforts);
    switch (key.kind) {
      case 'up':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: -1, count: models.length });
        return true;
      case 'down':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: 1, count: models.length });
        return true;
      case 'left':
        callbacks.dispatch({ type: 'commandPanelReasoningMoved', delta: -1, count: efforts.length });
        return true;
      case 'right':
        callbacks.dispatch({ type: 'commandPanelReasoningMoved', delta: 1, count: efforts.length });
        return true;
      case 'return': {
        const selected = models[clampIndex(panel.selectedIndex, models.length)];
        if (selected?.modelId) {
          await callbacks.setModel(
            selected.providerId ?? undefined,
            selected.modelId,
            efforts[clampIndex(panel.reasoningIndex, efforts.length)],
          );
        }
        return true;
      }
      default:
        return true;
    }
  }

  private handleSkillsKey(key: Key, panel: Extract<CommandPanel, { kind: 'skills' }>, callbacks: CommandPanelCallbacks): boolean {
    const skills = filterSkills(panel.skills, panel.query);
    switch (key.kind) {
      case 'up':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: -1, count: skills.length });
        return true;
      case 'down':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: 1, count: skills.length });
        return true;
      case 'backspace':
        callbacks.dispatch({ type: 'commandPanelQueryChanged', query: panel.query.slice(0, -1) });
        return true;
      case 'return':
        callbacks.dispatch({ type: 'commandPanelClosed', output: 'No changes' });
        return true;
      case 'text':
        if (key.value === '/' && !panel.query) {
          return true;
        }
        callbacks.dispatch({ type: 'commandPanelQueryChanged', query: `${panel.query}${key.value}` });
        return true;
      default:
        return true;
    }
  }

  private async handlePermissionsKey(
    key: Key,
    panel: Extract<CommandPanel, { kind: 'permissions' }>,
    callbacks: CommandPanelCallbacks,
  ): Promise<boolean> {
    const modes = panel.catalog.modes ?? [];
    switch (key.kind) {
      case 'up':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: -1, count: modes.length });
        return true;
      case 'down':
        callbacks.dispatch({ type: 'commandPanelSelectionMoved', delta: 1, count: modes.length });
        return true;
      case 'return': {
        const selected = modes[clampIndex(panel.selectedIndex, modes.length)];
        if (selected?.id) {
          await callbacks.setPermissionMode(selected.id);
        }
        return true;
      }
      default:
        return true;
    }
  }
}

function commandPanelCancelOutput(panel: CommandPanel): string {
  switch (panel.kind) {
    case 'help':
      return 'Help dialog dismissed';
    case 'resume':
      return 'Resume cancelled';
    case 'model':
      return `Kept model as ${modelSelectionLabel(panel.catalog.current)}`;
    case 'permissions':
      return `Kept permissions as ${permissionModeLabel(panel.catalog.current)}`;
    case 'skills':
      return 'Skills dialog dismissed';
  }
}

function modelSelectionLabel(selection: Extract<CommandPanel, { kind: 'model' }>['catalog']['current']): string {
  const provider = selection?.providerId?.trim();
  const model = selection?.modelId?.trim();
  return [provider, model].filter(Boolean).join('/') || model || 'current model';
}

function permissionModeLabel(mode: Extract<CommandPanel, { kind: 'permissions' }>['catalog']['current']): string {
  return mode?.name?.trim() || mode?.id?.trim() || 'current mode';
}
