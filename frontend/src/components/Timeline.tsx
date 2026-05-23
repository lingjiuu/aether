import React from 'react';
import { Box, Text } from 'ink';
import { useAppState } from '../state/store.js';
import { selectTurns } from '../state/selectors.js';
import { tokens } from '../theme/tokens.js';
import { MessageRow } from './MessageRow.js';

export function Timeline() {
  const state = useAppState();
  const turns = selectTurns(state);

  if (turns.length === 0) {
    return (
      <Box marginY={1}>
        <Text color={tokens.dim}>Start typing to talk with Aether.</Text>
      </Box>
    );
  }

  return (
    <Box flexDirection="column">
      {turns.map(turn => (
        <Box key={turn.turnId} flexDirection="column">
          {turn.items.map(item => (
            <MessageRow key={item.id} item={item} />
          ))}
        </Box>
      ))}
    </Box>
  );
}
