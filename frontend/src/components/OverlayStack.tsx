import React from 'react';
import { Box } from 'ink';
import { useAppState } from '../state/store.js';
import { ApprovalCard } from './ApprovalCard.js';
import { SessionPicker } from './SessionPicker.js';

export function OverlayStack({ onApprovalRespond }: { onApprovalRespond: (approved: boolean) => void }) {
  const state = useAppState();

  return (
    <Box flexDirection="column">
      {state.sessions.length ? <SessionPicker /> : null}
      {state.pendingApproval ? <ApprovalCard onRespond={onApprovalRespond} /> : null}
    </Box>
  );
}
