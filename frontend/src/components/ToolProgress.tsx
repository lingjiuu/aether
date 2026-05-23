import React from 'react';
import { Box, Text } from 'ink';
import type { UiToolUpdate } from '../protocol/wire.js';
import { formatLineStatus, tailLines } from '../utils/format.js';
import { MessageResponse } from './MessageResponse.js';

export function ToolProgress({ update }: { update?: UiToolUpdate }) {
  const lines = tailLines(update?.text, 5);
  if (!lines.length) {
    return (
      <MessageResponse height={1}>
        <Text dimColor>Running...</Text>
      </MessageResponse>
    );
  }
  const lineStatus = formatLineStatus(update?.text, lines.length);

  return (
    <MessageResponse>
      <Box flexDirection="column">
        <Box height={Math.min(5, lines.length)} flexDirection="column" overflow="hidden">
          <Text dimColor>{lines.join('\n')}</Text>
        </Box>
        {lineStatus ? <Text dimColor>{lineStatus}</Text> : null}
      </Box>
    </MessageResponse>
  );
}
