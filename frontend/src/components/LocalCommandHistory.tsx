import React from 'react';
import { Box, Text } from 'ink';
import type { LocalCommandEntry } from '../state/reducer.js';
import { tokens } from '../theme/tokens.js';
import { MessageResponse } from './MessageResponse.js';

export function LocalCommandEntryView({ entry }: { entry: LocalCommandEntry }) {
  return (
    <Box flexDirection="column" marginTop={1} width="100%">
      <CommandLine command={entry.command} />
      <MessageResponse height={1}>
        <Text color={tokens.dim}>{entry.output}</Text>
      </MessageResponse>
    </Box>
  );
}

export function CommandLine({ command }: { command: string }) {
  return (
    <Box width="100%" backgroundColor={tokens.userMessageBackground} paddingRight={1}>
      <Text color={tokens.dim} bold>
        {'❯ '}
      </Text>
      <Text bold>{command}</Text>
    </Box>
  );
}
