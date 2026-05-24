import React from 'react';
import { Box } from 'ink';
import { isVisibleTimelineItem } from '../domain/timeline.js';
import type { LocalCommandEntry } from '../state/reducer.js';
import { useAppState } from '../state/store.js';
import { selectTurns } from '../state/selectors.js';
import { LocalCommandEntryView } from './LocalCommandHistory.js';
import { MessageRow } from './MessageRow.js';
import { TurnStatus } from './TurnStatus.js';

export function Transcript() {
  const state = useAppState();
  const turns = selectTurns(state);
  const localEntries = state.localCommandEntries;

  if (turns.length === 0 && localEntries.length === 0) {
    return null;
  }

  return (
    <Box flexDirection="column" width="100%">
      {renderLocalEntries(localEntries, 0)}
      {turns.map((turn, index) => (
        <React.Fragment key={turn.turnId}>
          <Box flexDirection="column" marginBottom={1} width="100%">
            {turn.items.filter(isVisibleTimelineItem).map(item => (
              <MessageRow key={item.id} item={item} />
            ))}
            <TurnStatus turn={turn} />
          </Box>
          {renderLocalEntries(localEntries, index + 1)}
        </React.Fragment>
      ))}
    </Box>
  );
}

function renderLocalEntries(entries: LocalCommandEntry[], position: number) {
  return entries
    .filter(entry => entry.afterTurnOrderLength === position)
    .map(entry => <LocalCommandEntryView key={entry.id} entry={entry} />);
}
