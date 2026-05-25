import { filterSessions } from '../../domain/sessionSummary.js';
import type { AppAction, AppState, CommandPanel } from '../../state/reducer.js';
import type { Key } from '../input/inputParser.js';
import { clampIndex } from '../shared/terminalMath.js';

type CommandPanelCallbacks = {
  dispatch: (action: AppAction) => void;
  resumeSession: (sessionId: string) => Promise<void>;
  setModel: (providerId: string | undefined, modelId: string, reasoningEffort?: string) => Promise<void>;
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
    const efforts = panel.catalog.reasoningEfforts ?? [];
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
}

function commandPanelCancelOutput(panel: CommandPanel): string {
  switch (panel.kind) {
    case 'help':
      return 'Help dialog dismissed';
    case 'resume':
      return 'Resume cancelled';
    case 'model':
      return `Kept model as ${modelSelectionLabel(panel.catalog.current)}`;
  }
}

function modelSelectionLabel(selection: Extract<CommandPanel, { kind: 'model' }>['catalog']['current']): string {
  const provider = selection?.providerId?.trim();
  const model = selection?.modelId?.trim();
  return [provider, model].filter(Boolean).join('/') || model || 'current model';
}
