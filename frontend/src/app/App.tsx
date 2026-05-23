import React, { useCallback, useEffect } from 'react';
import { Box, useApp } from 'ink';
import type { AetherClient } from '../backend/AetherClient.js';
import { Composer } from '../components/Composer.js';
import { Footer } from '../components/Footer.js';
import { OverlayStack } from '../components/OverlayStack.js';
import { Transcript } from '../components/Transcript.js';
import { WelcomeCard } from '../components/WelcomeCard.js';
import { AppStateProvider, useAppDispatch, useAppState } from '../state/store.js';
import { boot, handleInput } from './runtime.js';

const COMMAND_SUGGESTION_LIMIT = 5;

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

  return (
    <Box flexDirection="column" width="100%">
      <WelcomeCard state={state} />
      <Transcript />
      <OverlayStack onApprovalRespond={onApprovalRespond} />
      <Composer
        state={state}
        disabled={Boolean(state.pendingApproval)}
        suggestionLimit={COMMAND_SUGGESTION_LIMIT}
        onSubmit={onSubmit}
        onChange={value => dispatch({ type: 'composerChanged', value })}
        onMoveSuggestion={(delta, count) => dispatch({ type: 'composerSuggestionMoved', delta, count })}
        onSelectSuggestion={index => dispatch({ type: 'composerSuggestionSelected', index })}
      />
      <Footer state={state} />
    </Box>
  );
}
