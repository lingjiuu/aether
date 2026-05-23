import React from 'react';
import { Box, Text } from 'ink';
import type { TimelineItem } from '../domain/timeline.js';
import { formatToolUseSummary } from '../utils/format.js';
import { ToolProgress } from './ToolProgress.js';
import { ToolResult } from './ToolResult.js';
import { ToolUseIndicator } from './ToolUseIndicator.js';

export function ToolCallCard({ item }: { item: TimelineItem }) {
  const toolCall = item.toolCall;
  const name = toolCall?.toolName ?? 'tool';
  const args = formatToolUseSummary(name, toolCall?.argumentsJson, 160);
  const isError =
    item.toolResult?.error || item.status === 'ERROR' || item.status === 'FAILED' || item.status === 'ABORTED';
  const isRunning = item.status === 'RUNNING' || (!item.toolResult && !isError);

  return (
    <Box flexDirection="column" marginTop={1} width="100%">
      <Box flexDirection="row" flexWrap="nowrap">
        <ToolUseIndicator isError={Boolean(isError)} isUnresolved={isRunning} />
        <Box flexShrink={0}>
          <Text bold wrap="truncate-end">
            {name}
          </Text>
        </Box>
        {args ? (
          <Box flexShrink={1}>
            <Text>({args})</Text>
          </Box>
        ) : null}
      </Box>
      {isRunning ? <ToolProgress result={item.toolResult} /> : <ToolResult result={item.toolResult} />}
    </Box>
  );
}
