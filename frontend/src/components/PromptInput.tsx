import React, { useState } from 'react';
import { Box, Text } from 'ink';
import TextInput from 'ink-text-input';
import { tokens } from '../theme/tokens.js';

export function PromptInput({ onSubmit, disabled }: { onSubmit: (input: string) => void; disabled?: boolean }) {
  const [value, setValue] = useState('');

  return (
    <Box marginTop={1}>
      <Text color={tokens.accent}>{disabled ? '...' : 'aether'} </Text>
      <TextInput
        value={value}
        onChange={setValue}
        onSubmit={text => {
          if (!text.trim() || disabled) {
            return;
          }
          setValue('');
          onSubmit(text);
        }}
      />
    </Box>
  );
}
