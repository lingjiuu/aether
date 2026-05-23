import React from 'react';
import { Box, Text } from 'ink';

export function AssistantText({ text }: { text: string }) {
  return (
    <Box marginTop={1}>
      <Text>{text}</Text>
    </Box>
  );
}
