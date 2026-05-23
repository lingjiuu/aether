import type { AppState } from './reducer.js';

export function selectTurns(state: AppState) {
  return state.turnOrder.map(turnId => state.turns[turnId]).filter(turn => turn !== undefined);
}

export function selectIsRunning(state: AppState): boolean {
  return selectTurns(state).some(turn => turn.status === 'RUNNING');
}

export function selectStatusText(state: AppState): string {
  const parts = [
    state.session.name || state.session.sessionId || 'aether',
    state.session.model,
    state.session.cwd,
    state.session.connectionStatus,
  ].filter(Boolean);
  return parts.join(' | ');
}
