import React from 'react';
import { Box, Text } from 'ink';
import type { TimelineTurn } from '../domain/timeline.js';
import { tokens } from '../theme/tokens.js';
import { formatElapsedTime } from '../utils/format.js';

const TURN_COMPLETION_VERBS = ['Brewed', 'Churned', 'Cogitated', 'Cooked', 'Worked'] as const;

export function TurnStatus({ turn }: { turn: TimelineTurn }) {
  if (turn.status === 'RUNNING') {
    return (
      <Box marginTop={1} flexDirection="row">
        <Box flexShrink={0} minWidth={2}>
          <Text color={tokens.dim}>✢</Text>
        </Box>
        <Text color={tokens.dim}>Working...</Text>
      </Box>
    );
  }

  if (turn.status !== 'COMPLETED' && turn.status !== 'ABORTED') {
    return null;
  }

  const duration = formatElapsedTime(turn.startedAtMs, turn.endedAtMs);
  const verb =
    TURN_COMPLETION_VERBS[(turn.turn ?? 0) % TURN_COMPLETION_VERBS.length] ?? TURN_COMPLETION_VERBS[0];
  const suffix = turn.status === 'ABORTED' ? 'interrupted' : duration ? `for ${duration}` : 'done';

  return (
    <Box marginTop={1} flexDirection="row">
      <Box flexShrink={0} minWidth={2}>
        <Text color={tokens.dim}>✻</Text>
      </Box>
      <Text color={tokens.dim}>{`${verb} ${suffix}`}</Text>
    </Box>
  );
}
