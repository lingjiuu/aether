import React from 'react';
import { Box, Text } from 'ink';
import type { AppState } from '../state/reducer.js';
import { tokens } from '../theme/tokens.js';

export function WelcomeCard({ state }: { state: AppState }) {
  const sessionName = state.session.name || 'Aether';
  const cwd = state.session.cwd ?? process.cwd();

  return (
    <Box flexDirection="column" marginBottom={1}>
      <Box>
        <Text bold>Welcome back!</Text>
      </Box>
      <Box>
        <Text color={tokens.dim} wrap="truncate-end">
          {state.session.model ?? 'Aether'} · {cwd}
        </Text>
      </Box>
      <Box>
        <Text color={tokens.dim} wrap="truncate-end">
          {sessionName} · Run /help to see commands
        </Text>
      </Box>
      <Box>
        <Text color={tokens.dim} wrap="truncate-end">
          Use /sessions to resume older work · Use /compact when context gets crowded
        </Text>
      </Box>
    </Box>
  );
}
