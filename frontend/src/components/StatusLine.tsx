import React from 'react';
import { Box, Text } from 'ink';
import { useAppState } from '../state/store.js';
import { selectIsRunning, selectStatusText } from '../state/selectors.js';
import { tokens } from '../theme/tokens.js';

export function StatusLine() {
  const state = useAppState();
  const running = selectIsRunning(state);
  const totalTokens = state.tokenUsage?.total?.totalTokens;
  return (
    <Box borderStyle="single" borderColor={tokens.border} paddingX={1}>
      <Text color={running ? tokens.warning : tokens.dim}>
        {running ? 'running' : 'idle'}
      </Text>
      <Text color={tokens.dim}> | {selectStatusText(state)}</Text>
      {totalTokens != null ? <Text color={tokens.dim}> | tokens {totalTokens}</Text> : null}
    </Box>
  );
}
