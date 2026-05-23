import React from 'react';
import { Box, Text } from 'ink';
import type { TimelineTurn } from '../domain/timeline.js';
import { tokens } from '../theme/tokens.js';
import { formatElapsedTime } from '../utils/format.js';

const TURN_COMPLETION_VERBS = ['Brewed', 'Churned', 'Cogitated', 'Cooked', 'Worked'] as const;
const RUNNING_FRAMES = ['✢', '✳', '✶', '✻'] as const;
const RUNNING_VERBS = ['Working', 'Thinking', 'Brewing', 'Cooking', 'Cogitating'] as const;

export function TurnStatus({ turn }: { turn: TimelineTurn }) {
  const [frame, setFrame] = React.useState(0);
  const runningVerb = React.useMemo(() => {
    return RUNNING_VERBS[hashText(turn.turnId) % RUNNING_VERBS.length] ?? RUNNING_VERBS[0];
  }, [turn.turnId]);

  React.useEffect(() => {
    if (turn.status !== 'RUNNING') {
      return undefined;
    }
    const interval = setInterval(() => {
      setFrame(current => (current + 1) % RUNNING_FRAMES.length);
    }, 480);
    return () => clearInterval(interval);
  }, [turn.status]);

  if (turn.status === 'RUNNING') {
    return (
      <Box width="100%" marginTop={1} flexDirection="row">
        <Box flexShrink={0} minWidth={2}>
          <Text color={tokens.dim}>{RUNNING_FRAMES[frame]}</Text>
        </Box>
        <Text color={tokens.dim}>
          {runningVerb}
          {'.'.repeat((frame % 3) + 1)}
        </Text>
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
    <Box width="100%" marginTop={1} flexDirection="row">
      <Box flexShrink={0} minWidth={2}>
        <Text color={tokens.dim}>✻</Text>
      </Box>
      <Text color={tokens.dim}>{`${verb} ${suffix}`}</Text>
    </Box>
  );
}

function hashText(text: string): number {
  let hash = 0;
  for (const char of text) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  }
  return hash;
}
