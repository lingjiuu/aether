import React from 'react';
import { Box } from 'ink';
import { useAppState } from '../state/store.js';
import { ApprovalCard } from './ApprovalCard.js';

export function OverlayStack({ onApprovalRespond }: { onApprovalRespond: (approved: boolean) => void }) {
  const state = useAppState();

  return (
    <Box flexDirection="column">
      {state.pendingApproval ? <ApprovalCard onRespond={onApprovalRespond} /> : null}
    </Box>
  );
}
