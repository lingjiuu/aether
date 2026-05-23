import React, { useCallback, useEffect } from 'react';
import { useApp } from 'ink';
import type { AetherClient } from '../backend/AetherClient.js';
import { Layout } from '../components/Layout.js';
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

  return <Layout onSubmit={onSubmit} />;
}
