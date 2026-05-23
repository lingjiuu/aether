import React from 'react';
import { Box, Text } from 'ink';
import { tokens } from '../theme/tokens.js';

export function UserMessage({ text }: { text: string }) {
  return (
    <Box width="100%" marginTop={1} backgroundColor={tokens.userMessageBackground} paddingRight={1}>
      <Text color={tokens.dim} bold>
        {'❯ '}
      </Text>
      <Text bold>{text}</Text>
    </Box>
  );
}
