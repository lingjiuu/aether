import React from 'react';
import { Box, Text } from 'ink';
import { tokens } from '../theme/tokens.js';

type Props = {
  isError: boolean;
  isUnresolved: boolean;
};

const BLACK_CIRCLE = '●';
const RUNNING_FRAMES = ['●', '∙', '·', '∙'] as const;

export function ToolUseIndicator({ isError, isUnresolved }: Props) {
  const [frame, setFrame] = React.useState(0);

  React.useEffect(() => {
    if (!isUnresolved || isError) {
      setFrame(0);
      return undefined;
    }
    const interval = setInterval(() => {
      setFrame(current => (current + 1) % RUNNING_FRAMES.length);
    }, 480);
    return () => clearInterval(interval);
  }, [isError, isUnresolved]);

  const color = isUnresolved ? undefined : isError ? tokens.error : tokens.success;
  const symbol = isUnresolved ? RUNNING_FRAMES[frame] : BLACK_CIRCLE;

  return (
    <Box minWidth={2}>
      <Text color={color} dimColor={isUnresolved}>
        {symbol}
      </Text>
    </Box>
  );
}
