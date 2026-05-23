import React from 'react';
import { Box, Text } from 'ink';
import type { UiToolUpdate } from '../protocol/wire.js';
import { formatLineStatus, tailLines } from '../utils/format.js';
import { MessageResponse } from './MessageResponse.js';

export function ToolProgress({ update }: { update?: UiToolUpdate }) {
  const [frame, setFrame] = React.useState(0);

  React.useEffect(() => {
    const interval = setInterval(() => {
      setFrame(current => (current + 1) % 4);
    }, 480);
    return () => clearInterval(interval);
  }, []);

  const lines = tailLines(update?.text, 5);
  if (!lines.length) {
    const suffix = '.'.repeat((frame % 3) + 1);
    return (
      <MessageResponse height={1}>
        <Text dimColor>{`Running${suffix}`}</Text>
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
