import React from 'react';
import { Box, Text } from 'ink';
import { useAppState } from '../state/store.js';
import { selectTurns } from '../state/selectors.js';
import { tokens } from '../theme/tokens.js';
import { MessageRow } from './MessageRow.js';
import { TurnStatus } from './TurnStatus.js';

export function Transcript() {
  const state = useAppState();
  const turns = selectTurns(state).slice(-8);

  if (turns.length === 0) {
    return (
      <Box flexDirection="column" marginBottom={1}>
        <Text color={tokens.dim}>Start with a prompt or a slash command.</Text>
      </Box>
    );
  }

  return (
    <Box flexDirection="column" flexGrow={1}>
      {turns.map(turn => (
        <Box key={turn.turnId} flexDirection="column" marginBottom={1}>
          {turn.items.map(item => (
            <MessageRow key={item.id} item={item} />
          ))}
          <TurnStatus turn={turn} />
        </Box>
      ))}
    </Box>
  );
}
