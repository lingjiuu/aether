import React from 'react';
import { Box, Text } from 'ink';

export function AssistantText({ text }: { text: string }) {
  return (
    <Box marginTop={1} flexDirection="row">
      <Box flexShrink={0} minWidth={2}>
        <Text>●</Text>
      </Box>
      <Text>{text}</Text>
    </Box>
  );
}
