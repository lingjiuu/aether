import React from 'react';
import { Box } from 'ink';
import { isVisibleTimelineItem } from '../domain/timeline.js';
import { useAppState } from '../state/store.js';
import { selectTurns } from '../state/selectors.js';
import { MessageRow } from './MessageRow.js';
import { TurnStatus } from './TurnStatus.js';

export function Transcript() {
  const state = useAppState();
  const turns = selectTurns(state);

  if (turns.length === 0) {
    return null;
  }

  return (
    <Box flexDirection="column" width="100%">
      {turns.map(turn => (
        <Box key={turn.turnId} flexDirection="column" marginBottom={1} width="100%">
          {turn.items.filter(isVisibleTimelineItem).map(item => (
            <MessageRow key={item.id} item={item} />
          ))}
          <TurnStatus turn={turn} />
        </Box>
      ))}
    </Box>
  );
}
