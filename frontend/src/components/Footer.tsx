import React from 'react';
import { Box, Text } from 'ink';
import type { AppState } from '../state/reducer.js';
import { selectIsRunning } from '../state/selectors.js';
import { tokens } from '../theme/tokens.js';

export function Footer({ state }: { state: AppState }) {
  const running = selectIsRunning(state);

  return (
    <Box justifyContent="space-between" paddingX={2} width="100%" height={1} flexShrink={0}>
      <Text color={tokens.dim}>{running ? 'esc to interrupt' : '? for shortcuts'}</Text>
      <Text color={tokens.dim} wrap="truncate-end">
        {state.notices.at(-1) ?? ' '}
      </Text>
    </Box>
  );
}
