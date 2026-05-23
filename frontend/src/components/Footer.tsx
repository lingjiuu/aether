import React from 'react';
import { Box, Text } from 'ink';
import type { AppState } from '../state/reducer.js';
import { selectIsRunning } from '../state/selectors.js';
import { tokens } from '../theme/tokens.js';

export function Footer({ state }: { state: AppState }) {
  const running = selectIsRunning(state);
  const latestNotice = state.notices.at(-1);
  const totalTokens = state.tokenUsage?.total?.totalTokens;
  const right = latestNotice ?? (totalTokens != null ? `${totalTokens} tokens` : state.session.model);

  return (
    <Box justifyContent="space-between" paddingX={2}>
      <Text color={tokens.dim}>{running ? 'esc to interrupt' : '? for shortcuts'}</Text>
      <Text color={latestNotice ? tokens.warning : tokens.dim} wrap="truncate-end">
        {right ?? ' '}
      </Text>
    </Box>
  );
}
