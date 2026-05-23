import React from 'react';
import { Box, Text } from 'ink';
import { useAppState } from '../state/store.js';
import { ApprovalCard } from './ApprovalCard.js';
import { PromptInput } from './PromptInput.js';
import { SessionPicker } from './SessionPicker.js';
import { StatusLine } from './StatusLine.js';
import { Timeline } from './Timeline.js';
import { tokens } from '../theme/tokens.js';

export function Layout({ onSubmit }: { onSubmit: (input: string) => void }) {
  const state = useAppState();
  return (
    <Box flexDirection="column">
      <StatusLine />
      <Timeline />
      <SessionPicker />
      {state.notices.map((notice, index) => (
        <Text key={`${notice}-${index}`} color={tokens.dim}>
          {notice}
        </Text>
      ))}
      <ApprovalCard />
      <PromptInput onSubmit={onSubmit} disabled={state.session.connectionStatus !== 'connected'} />
    </Box>
  );
}
