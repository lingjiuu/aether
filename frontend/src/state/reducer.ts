import { sessionViewFromWire } from '../domain/session.js';
import { applyEvent, applyHistory } from './timelineReducer.js';
import {
  appendLocalCommand,
  commandPanelClosed,
  commandPanelOpened,
  commandPanelReasoningMoved,
  commandPanelQueryChanged,
  commandPanelSelectionMoved,
  composerChanged,
  composerSuggestionMoved,
  composerSuggestionSelected,
  noticeAdded,
} from './uiReducer.js';
import type { AppAction, AppState } from './types.js';

export type {
  AppAction,
  AppState,
  CommandPanel,
  ComposerState,
  LocalCommandEntry,
} from './types.js';
export { initialState } from './types.js';

export function reducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'connected':
      return {
        ...state,
        session: {
          ...state.session,
          ...sessionViewFromWire(action.session),
          connectionStatus: 'connected',
        },
      };
    case 'disconnected':
      return {
        ...state,
        session: { ...state.session, connectionStatus: 'disconnected' },
      };
    case 'sessionState':
      return {
        ...state,
        session: {
          ...state.session,
          ...sessionViewFromWire(action.session),
        },
        tokenUsage: action.session.tokenUsage ?? state.tokenUsage,
      };
    case 'history':
      return applyHistory(state, action.history);
    case 'event':
      return applyEvent(state, action.event);
    case 'composerChanged':
      return composerChanged(state, action.value);
    case 'composerSuggestionMoved':
      return composerSuggestionMoved(state, action.delta, action.count);
    case 'composerSuggestionSelected':
      return composerSuggestionSelected(state, action.index);
    case 'commandPanelOpened':
      return commandPanelOpened(state, action.panel);
    case 'commandPanelSelectionMoved':
      return commandPanelSelectionMoved(state, action.delta, action.count);
    case 'commandPanelReasoningMoved':
      return commandPanelReasoningMoved(state, action.delta, action.count);
    case 'commandPanelQueryChanged':
      return commandPanelQueryChanged(state, action.query);
    case 'commandPanelClosed':
      return commandPanelClosed(state, action.output);
    case 'localCommandCompleted':
      return appendLocalCommand(
        state,
        {
          id: action.id,
          command: action.command,
          output: action.output,
        },
        state.commandPanel,
      );
    case 'notice':
      return noticeAdded(state, action.message);
  }
}
