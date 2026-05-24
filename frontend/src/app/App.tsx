import React, { useCallback, useEffect } from 'react';
import { Box, useApp } from 'ink';
import type { AetherClient } from '../backend/AetherClient.js';
import { CommandPanel } from '../components/CommandPanel.js';
import { Composer } from '../components/Composer.js';
import { Footer } from '../components/Footer.js';
import { OverlayStack } from '../components/OverlayStack.js';
import { Transcript } from '../components/Transcript.js';
import { WelcomeCard } from '../components/WelcomeCard.js';
import { AppStateProvider, useAppDispatch, useAppState } from '../state/store.js';
import { boot, handleInput } from './runtime.js';

export function App({ client }: { client: AetherClient }) {
  return (
    <AppStateProvider>
      <AppRuntime client={client} />
    </AppStateProvider>
  );
}

function AppRuntime({ client }: { client: AetherClient }) {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const { exit } = useApp();

  useEffect(() => {
    let active = true;
    boot(client, action => {
      if (active) {
        dispatch(action);
      }
    }).catch(error => {
      dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
      dispatch({ type: 'disconnected' });
    });

    return () => {
      active = false;
      client.close();
    };
  }, [client, dispatch]);

  const onSubmit = useCallback(
    (input: string) => {
      handleInput(input, state, client, dispatch, () => {
        client.close();
        exit();
      }).catch(error => {
        dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
      });
    },
    [client, dispatch, exit, state],
  );

  const onApprovalRespond = useCallback(
    (approved: boolean) => {
      const approvalId = state.pendingApproval?.request.approvalId;
      if (!approvalId) {
        return;
      }
      client.respondToApproval(approvalId, approved).catch(error => {
        dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
      });
    },
    [client, dispatch, state.pendingApproval?.request.approvalId],
  );

  const onCommandPanelClose = useCallback(
    (output: string) => {
      dispatch({ type: 'commandPanelClosed', output });
    },
    [dispatch],
  );

  const onResumeSession = useCallback(
    (sessionId: string) => {
      client
        .resume(sessionId)
        .then(ack => {
          if (ack.history) {
            dispatch({ type: 'history', history: ack.history });
          } else {
            dispatch({ type: 'commandPanelClosed', output: ack.message ?? `Resumed ${sessionId}` });
          }
          if (ack.message) {
            dispatch({ type: 'notice', message: ack.message });
          }
        })
        .catch(error => {
          dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
        });
    },
    [client, dispatch],
  );

  return (
    <Box flexDirection="column" width="100%">
      <WelcomeCard state={state} />
      <Transcript />
      <OverlayStack onApprovalRespond={onApprovalRespond} />
      {state.commandPanel ? (
        <CommandPanel
          panel={state.commandPanel}
          onClose={onCommandPanelClose}
          onMoveSelection={(delta, count) => dispatch({ type: 'commandPanelSelectionMoved', delta, count })}
          onQueryChange={query => dispatch({ type: 'commandPanelQueryChanged', query })}
          onResume={onResumeSession}
        />
      ) : (
        <Composer
          state={state}
          disabled={Boolean(state.pendingApproval)}
          onSubmit={onSubmit}
          onChange={value => dispatch({ type: 'composerChanged', value })}
          onMoveSuggestion={(delta, count) => dispatch({ type: 'composerSuggestionMoved', delta, count })}
          onSelectSuggestion={index => dispatch({ type: 'composerSuggestionSelected', index })}
        />
      )}
      <Footer state={state} />
    </Box>
  );
}
