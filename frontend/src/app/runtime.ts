import type { AetherClient } from '../backend/AetherClient.js';
import type { AppAction, AppState } from '../state/reducer.js';
import { parseCommand } from '../protocol/commands.js';
import { selectableReasoningEfforts } from '../domain/modelCatalog.js';
import { selectIsRunning } from '../state/selectors.js';

export async function boot(client: AetherClient, dispatch: (action: AppAction) => void): Promise<void> {
  client.start();
  client.onEvent(event => dispatch({ type: 'event', event }));
  client.onStderr(text => {
    const trimmed = text.trim();
    if (trimmed) {
      dispatch({ type: 'notice', message: trimmed });
    }
  });

  const initialized = await client.initialize();
  dispatch({ type: 'connected', session: initialized.session });
  if (initialized.history) {
    dispatch({ type: 'history', history: initialized.history });
  }
  if (!initialized.session) {
    dispatch({ type: 'sessionState', session: await client.currentSession() });
  }
  await client.initialized();
}

export async function handleInput(
  input: string,
  state: AppState,
  client: AetherClient,
  dispatch: (action: AppAction) => void,
  quit: () => void,
): Promise<void> {
  const command = parseCommand(input);

  switch (command.kind) {
    case 'submit':
      await client.submit(command.text);
      break;
    case 'new': {
      const cwd = state.session.cwd?.trim();
      if (!cwd) {
        dispatch({ type: 'notice', message: 'Cannot create a new session before the current cwd is known.' });
        break;
      }
      const ack = await client.newSession(cwd);
      if (ack.history) {
        dispatch({ type: 'history', history: ack.history });
      }
      dispatch({ type: 'sessionState', session: await client.currentSession() });
      break;
    }
    case 'resume': {
      if (!command.sessionId) {
        dispatch({
          type: 'commandPanelOpened',
          panel: {
            kind: 'resume',
            id: localCommandId('resume'),
            command: '/resume',
            sessions: await client.listSessions(),
            selectedIndex: 0,
            query: '',
          },
        });
        break;
      }
      const ack = await client.resume(command.sessionId);
      if (ack.history) {
        dispatch({ type: 'history', history: ack.history });
      }
      dispatch({ type: 'sessionState', session: await client.currentSession() });
      break;
    }
    case 'sessions':
      dispatch({ type: 'sessions', sessions: await client.listSessions() });
      break;
    case 'compact':
      await client.compact();
      break;
    case 'cancel':
      await client.cancelTurn();
      break;
    case 'skills': {
      const skills = await client.listSkills();
      dispatch({
        type: 'notice',
        message: skills.length ? skills.map(skill => skill.name).filter(Boolean).join(', ') : 'No skills found.',
      });
      break;
    }
    case 'reloadSkills':
      await client.reloadSkills();
      break;
    case 'model': {
      if (selectIsRunning(state) || state.session.status === 'RUNNING') {
        recordLocalCommand(input, "'/model' is disabled while a task is in progress.", dispatch);
        break;
      }
      const catalog = await client.listModels();
      dispatch({
        type: 'commandPanelOpened',
        panel: {
          kind: 'model',
          id: localCommandId('model'),
          command: '/model',
          catalog,
          selectedIndex: selectedModelIndex(catalog),
          reasoningIndex: selectedReasoningIndex(catalog),
        },
      });
      break;
    }
    case 'name': {
      if (!command.name.trim()) {
        recordLocalCommand(input, 'Session name is required. Usage: /rename [name]', dispatch);
        break;
      }
      const ack = await client.setSessionName(command.name.trim());
      if (ack.message) {
        recordLocalCommand(input, ack.message, dispatch);
      }
      break;
    }
    case 'approve':
      await respondToPendingApproval(state, client, true);
      break;
    case 'deny':
      await respondToPendingApproval(state, client, false);
      break;
    case 'help':
      dispatch({
        type: 'commandPanelOpened',
        panel: { kind: 'help', id: localCommandId('help'), command: '/help' },
      });
      break;
    case 'quit':
      quit();
      break;
    case 'unknown':
      recordLocalCommand(input, `Unknown command: /${command.name}`, dispatch);
      break;
  }
}

type ModelCatalog = Awaited<ReturnType<AetherClient['listModels']>>;

function selectedModelIndex(catalog: ModelCatalog): number {
  const models = catalog.models ?? [];
  const current = catalog.current;
  const index = models.findIndex(model =>
    model.current
    || (model.providerId === current?.providerId && model.modelId === current?.modelId),
  );
  return Math.max(0, index);
}

function selectedReasoningIndex(catalog: ModelCatalog): number {
  const efforts = selectableReasoningEfforts(catalog.reasoningEfforts);
  const current = catalog.current?.reasoningEffort;
  const index = efforts.findIndex(effort => effort.toLowerCase() === current?.toLowerCase());
  return Math.max(0, index);
}

function localCommandId(name: string): string {
  return `local-${name}-${Date.now()}`;
}

function recordLocalCommand(input: string, output: string, dispatch: (action: AppAction) => void): void {
  dispatch({
    type: 'localCommandCompleted',
    id: localCommandId('command'),
    command: input.trim(),
    output,
  });
}

async function respondToPendingApproval(state: AppState, client: AetherClient, approved: boolean): Promise<void> {
  const approvalId = state.pendingApproval?.request.approvalId;
  if (!approvalId) {
    return;
  }
  await client.respondToApproval(approvalId, approved);
}
