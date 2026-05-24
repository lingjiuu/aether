import { filterSessions } from '../../domain/sessionSummary.js';
import type { AppAction, AppState, CommandPanel } from '../../state/reducer.js';
import type { Key } from '../input/inputParser.js';
import { clampIndex } from '../shared/terminalMath.js';

type CommandPanelCallbacks = {
  dispatch: (action: AppAction) => void;
  resumeSession: (sessionId: string) => Promise<void>;
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
}

function commandPanelCancelOutput(panel: CommandPanel): string {
  return panel.kind === 'help' ? 'Help dialog dismissed' : 'Resume cancelled';
}
