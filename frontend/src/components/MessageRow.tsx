import React from 'react';
import { Box, Text } from 'ink';
import type { TimelineItem } from '../domain/timeline.js';
import { tokens } from '../theme/tokens.js';
import { AssistantText } from './AssistantText.js';
import { ToolCallCard } from './ToolCallCard.js';
import { UserMessage } from './UserMessage.js';

export function MessageRow({ item }: { item: TimelineItem }) {
  switch (item.kind) {
    case 'USER_MESSAGE':
      return <UserMessage text={item.text} />;
    case 'ASSISTANT_TEXT':
      return <AssistantText text={item.text} />;
    case 'REASONING':
      return item.text ? (
        <Box marginTop={1} flexDirection="row">
          <Box flexShrink={0} minWidth={2}>
            <Text color={tokens.dim}>✻</Text>
          </Box>
          <Text color={tokens.dim}>{item.text}</Text>
        </Box>
      ) : null;
    case 'TOOL_CALL':
      return <ToolCallCard item={item} />;
    case 'TOOL_RESULT':
      return null;
    case 'CONTEXT_MESSAGE':
      return item.text ? (
        <Box marginTop={1}>
          <Text color={tokens.dim}>{item.text}</Text>
        </Box>
      ) : null;
  }
}
