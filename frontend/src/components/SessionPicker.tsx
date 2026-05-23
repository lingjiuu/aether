import React from 'react';
import { Box, Text } from 'ink';
import { useAppState } from '../state/store.js';
import { tokens } from '../theme/tokens.js';

export function SessionPicker() {
  const sessions = useAppState().sessions;
  if (sessions.length === 0) {
    return null;
  }
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={tokens.border} paddingX={1} marginTop={1}>
      <Text bold>Sessions</Text>
      {sessions.slice(0, 8).map(session => (
        <Text key={session.sessionId ?? session.name} color={tokens.dim}>
          {session.sessionId} {session.name ? `- ${session.name}` : ''} {session.preview ? `- ${session.preview}` : ''}
        </Text>
      ))}
    </Box>
  );
}
