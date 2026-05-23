import React from 'react';
import { Box, Text } from 'ink';
import { MarkdownText } from './MarkdownText.js';

export function AssistantText({ text }: { text: string }) {
  return (
    <Box width="100%" marginTop={1} flexDirection="row">
      <Box flexShrink={0} minWidth={2}>
        <Text>●</Text>
      </Box>
      <Box flexDirection="column" flexShrink={1}>
        <MarkdownText text={text} />
      </Box>
    </Box>
  );
}
