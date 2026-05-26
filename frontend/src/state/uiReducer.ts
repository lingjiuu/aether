import type { AppState, CommandPanel, ComposerPopup, LocalCommandEntry } from './types.js';

export function composerChanged(state: AppState, value: string): AppState {
  return {
    ...state,
    composer: {
      value,
      commandPaletteOpen: value.trimStart().startsWith('/'),
      popup: undefined,
      selectedSuggestionIndex: 0,
    },
  };
}

export function composerPopupChanged(state: AppState, popup?: ComposerPopup): AppState {
  return {
    ...state,
    composer: {
      ...state.composer,
      commandPaletteOpen: state.composer.commandPaletteOpen && !popup,
      popup,
      selectedSuggestionIndex: 0,
    },
  };
}

export function composerSuggestionMoved(state: AppState, delta: -1 | 1, count: number): AppState {
  return {
    ...state,
    composer: {
      ...state.composer,
      selectedSuggestionIndex: moveIndex(state.composer.selectedSuggestionIndex, delta, count),
    },
  };
}

export function composerSuggestionSelected(state: AppState, index: number): AppState {
  return {
    ...state,
    composer: {
      ...state.composer,
      selectedSuggestionIndex: Math.max(0, index),
    },
  };
}

export function commandPanelOpened(state: AppState, panel: CommandPanel): AppState {
  return {
    ...state,
    commandPanel: panel,
    composer: {
      ...state.composer,
      value: '',
      commandPaletteOpen: false,
      popup: undefined,
      selectedSuggestionIndex: 0,
    },
  };
}

export function commandPanelSelectionMoved(state: AppState, delta: -1 | 1, count: number): AppState {
  if (state.commandPanel?.kind !== 'resume' && state.commandPanel?.kind !== 'model' && state.commandPanel?.kind !== 'skills') {
    return state;
  }
  return {
    ...state,
    commandPanel: {
      ...state.commandPanel,
      selectedIndex: moveIndex(state.commandPanel.selectedIndex, delta, count),
    },
  };
}

export function commandPanelReasoningMoved(state: AppState, delta: -1 | 1, count: number): AppState {
  return state.commandPanel?.kind === 'model'
    ? { ...state, commandPanel: { ...state.commandPanel, reasoningIndex: moveIndex(state.commandPanel.reasoningIndex, delta, count) } }
    : state;
}

export function commandPanelQueryChanged(state: AppState, query: string): AppState {
  return state.commandPanel?.kind === 'resume' || state.commandPanel?.kind === 'skills'
    ? { ...state, commandPanel: { ...state.commandPanel, query, selectedIndex: 0 } }
    : state;
}

export function commandPanelClosed(state: AppState, output: string): AppState {
  const panel = state.commandPanel;
  if (!panel) {
    return state;
  }
  return appendLocalCommand(
    state,
    {
      id: panel.id,
      command: panel.command,
      output,
    },
    undefined,
  );
}

export function appendLocalCommand(
  state: AppState,
  entry: Pick<LocalCommandEntry, 'id' | 'command' | 'output'>,
  commandPanel: CommandPanel | undefined,
): AppState {
  return {
    ...state,
    commandPanel,
    localCommandEntries: [
      ...state.localCommandEntries,
      {
        ...entry,
        afterTurnOrderLength: state.turnOrder.length,
      },
    ],
  };
}

export function noticeAdded(state: AppState, message: string): AppState {
  return { ...state, notices: [...state.notices.slice(-4), message] };
}

export function localCommandId(name: string): string {
  return `local-${name}-${Date.now()}`;
}

function moveIndex(current: number, delta: -1 | 1, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return (current + delta + count) % count;
}
