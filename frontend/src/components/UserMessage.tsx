import React from 'react';
import { Box, Text } from 'ink';
import { tokens } from '../theme/tokens.js';

export function UserMessage({ text }: { text: string }) {
  return (
    <Box marginTop={1}>
      <Text color={tokens.accent} bold>
        {'> '}
      </Text>
      <Text>{text}</Text>
    </Box>
  );
}
