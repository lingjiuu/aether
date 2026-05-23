import React, { useContext } from 'react';
import { Box, Text } from 'ink';

type Props = {
  children: React.ReactNode;
  height?: number;
};

const MessageResponseContext = React.createContext(false);

export function MessageResponse({ children, height }: Props) {
  const nested = useContext(MessageResponseContext);
  if (nested) {
    return children;
  }

  return (
    <MessageResponseContext.Provider value={true}>
      <Box flexDirection="row" height={height} overflowY="hidden">
        <Box flexShrink={0}>
          <Text dimColor>{'  '}⎿  </Text>
        </Box>
        <Box flexShrink={1} flexGrow={1}>
          {children}
        </Box>
      </Box>
    </MessageResponseContext.Provider>
  );
}
