import React from 'react';
import { Box, Text } from 'ink';
import type { AppState } from '../state/reducer.js';
import { tokens } from '../theme/tokens.js';

export function WelcomeCard({ state }: { state: AppState }) {
  const sessionName = state.session.name || 'Aether';
  const cwd = state.session.cwd ?? process.cwd();

  return (
    <Box borderStyle="round" borderColor={tokens.border} paddingX={2} paddingY={1} marginBottom={1}>
      <Box flexDirection="column" width="42%">
        <Text bold>Welcome back!</Text>
        <Text color={tokens.accent}>Aether</Text>
        <Text color={tokens.dim} wrap="truncate-end">
          {sessionName}
        </Text>
        <Text color={tokens.dim} wrap="truncate-end">
          {cwd}
        </Text>
      </Box>
      <Box flexDirection="column" flexGrow={1}>
        <Text bold>Tips for getting started</Text>
        <Text color={tokens.dim}>Run /help to see commands</Text>
        <Text color={tokens.dim}>Use /sessions to resume older work</Text>
        <Text color={tokens.dim}>Use /compact when context gets crowded</Text>
      </Box>
    </Box>
  );
}
