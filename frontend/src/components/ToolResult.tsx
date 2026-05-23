import React from 'react';
import { Box, Text } from 'ink';
import type { UiToolResult } from '../protocol/wire.js';
import { formatDuration, formatLineStatus, normalizeNoOutput, tailLines } from '../utils/format.js';
import { tokens } from '../theme/tokens.js';
import { MessageResponse } from './MessageResponse.js';

export function ToolResult({ result }: { result?: UiToolResult }) {
  if (!result) {
    return null;
  }
  const duration = formatDuration(result.durationMs);
  const text = normalizeNoOutput(result.text);
  const noOutput = !text || text === '(No output)';
  const lines = noOutput ? [] : tailLines(text, 5);
  const lineStatus = formatLineStatus(text, lines.length);
  const statusText = result.error ? 'Error' : noOutput ? 'Done' : '';
  const hasMetadata = Boolean(lineStatus || duration || result.truncated);

  return (
    <Box flexDirection="column">
      {lines.length ? (
        <MessageResponse>
          <Box flexDirection="column">
            <Text color={result.error ? tokens.error : undefined}>{lines.join('\n')}</Text>
            {hasMetadata ? (
              <Box flexDirection="row" gap={1}>
                {lineStatus ? <Text dimColor>{lineStatus}</Text> : null}
                {duration ? <Text dimColor>{duration}</Text> : null}
                {result.truncated ? <Text dimColor>truncated</Text> : null}
              </Box>
            ) : null}
          </Box>
        </MessageResponse>
      ) : (
        <MessageResponse height={1}>
          <Text color={result.error ? tokens.error : undefined} dimColor={!result.error}>
            {statusText || text}
            {duration ? ` ${duration}` : ''}
            {result.truncated ? ' truncated' : ''}
          </Text>
        </MessageResponse>
      )}
    </Box>
  );
}
