import type { AetherClient } from '../backend/AetherClient.js';
import type { AppAction, AppState } from '../state/reducer.js';
import { parseCommand } from '../protocol/commands.js';

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
    case 'continue':
      await client.continueTurn();
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
    case 'name':
      await client.setSessionName(command.name);
      break;
    case 'approve':
      await respondToPendingApproval(state, client, true);
      break;
    case 'deny':
      await respondToPendingApproval(state, client, false);
      break;
    case 'help':
      dispatch({
        type: 'notice',
        message: '/new /resume <id> /sessions /compact /continue /cancel /skills /reload-skills /name <text> /approve /deny /quit',
      });
      break;
    case 'quit':
      quit();
      break;
    case 'unknown':
      dispatch({ type: 'notice', message: `Unknown command: /${command.name}` });
      break;
  }
}

async function respondToPendingApproval(state: AppState, client: AetherClient, approved: boolean): Promise<void> {
  const approvalId = state.pendingApproval?.request.approvalId;
  if (!approvalId) {
    return;
  }
  await client.respondToApproval(approvalId, approved);
}
