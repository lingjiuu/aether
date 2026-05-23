import React, { useMemo } from 'react';
import { Box, Text } from 'ink';
import TextInput from 'ink-text-input';
import type { AppState } from '../state/reducer.js';
import { getSlashCommandSuggestions } from '../commands/slashCommands.js';
import { tokens } from '../theme/tokens.js';

type Props = {
  state: AppState;
  onSubmit: (input: string) => void;
  onChange: (value: string) => void;
};

export function Composer({ state, onSubmit, onChange }: Props) {
  const value = state.composer.value;
  const suggestions = useMemo(
    () => (state.composer.commandPaletteOpen ? getSlashCommandSuggestions(value, 5) : []),
    [state.composer.commandPaletteOpen, value],
  );
  const connected = state.session.connectionStatus === 'connected';

  return (
    <Box flexDirection="column">
      {suggestions.length ? (
        <Box flexDirection="column" marginBottom={1} paddingX={2}>
          {suggestions.map(command => (
            <Text key={command.usage} color={tokens.dim}>
              {command.usage}
            </Text>
          ))}
        </Box>
      ) : null}
      <Box borderTop borderColor={tokens.border} paddingX={2}>
        <Text color={connected ? tokens.dim : tokens.warning} bold>
          ❯{' '}
        </Text>
        <TextInput
          value={value}
          onChange={next => {
            onChange(next);
          }}
          onSubmit={text => {
            const trimmed = text.trim();
            if (!trimmed) {
              return;
            }
            onChange('');
            onSubmit(trimmed);
          }}
          placeholder={connected ? 'Try "/help" or ask Aether to do something' : 'Connecting...'}
          focus={connected}
          showCursor={connected}
        />
      </Box>
    </Box>
  );
}
