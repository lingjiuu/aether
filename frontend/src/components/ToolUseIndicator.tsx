import React from 'react';
import { Box, Text } from 'ink';
import { tokens } from '../theme/tokens.js';

type Props = {
  isError: boolean;
  isUnresolved: boolean;
};

const BLACK_CIRCLE = '●';

export function ToolUseIndicator({ isError, isUnresolved }: Props) {
  const [visible, setVisible] = React.useState(true);

  React.useEffect(() => {
    if (!isUnresolved || isError) {
      setVisible(true);
      return undefined;
    }
    const interval = setInterval(() => {
      setVisible(current => !current);
    }, 450);
    return () => clearInterval(interval);
  }, [isError, isUnresolved]);

  const color = isUnresolved ? undefined : isError ? tokens.error : tokens.success;
  const symbol = isUnresolved && !visible ? ' ' : BLACK_CIRCLE;

  return (
    <Box minWidth={2}>
      <Text color={color} dimColor={isUnresolved}>
        {symbol}
      </Text>
    </Box>
  );
}
