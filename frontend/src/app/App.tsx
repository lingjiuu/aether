import React, { useCallback, useEffect } from 'react';
import { Box, useApp, useStdout } from 'ink';
import type { AetherClient } from '../backend/AetherClient.js';
import { Composer } from '../components/Composer.js';
import { Footer } from '../components/Footer.js';
import { OverlayStack } from '../components/OverlayStack.js';
import { Transcript } from '../components/Transcript.js';
import { WelcomeCard } from '../components/WelcomeCard.js';
import { selectTurns } from '../state/selectors.js';
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
  const { stdout } = useStdout();

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

  const hasTurns = selectTurns(state).length > 0;

  return (
    <Box flexDirection="column" height={stdout.rows}>
      <Box flexDirection="column" flexGrow={1} overflowY="hidden">
        {!hasTurns ? <WelcomeCard state={state} /> : null}
        <Transcript />
        <OverlayStack />
      </Box>
      <Composer
        state={state}
        onSubmit={onSubmit}
          onChange={value => dispatch({ type: 'composerChanged', value })}
      />
      <Footer state={state} />
    </Box>
  );
}
