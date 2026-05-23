import React from 'react';
import { Box, Text } from 'ink';
import { useAppState } from '../state/store.js';
import { safeJson } from '../utils/json.js';
import { compactText } from '../utils/format.js';
import { tokens } from '../theme/tokens.js';

export function ApprovalCard() {
  const approval = useAppState().pendingApproval;
  if (!approval) {
    return null;
  }
  const request = approval.request;
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={tokens.warning} paddingX={1} marginTop={1}>
      <Text color={tokens.warning} bold>
        Approval required
      </Text>
      <Text>
        {request.toolName ?? 'tool'} {request.riskLevel ? `(${request.riskLevel})` : ''}
      </Text>
      {request.reason ? <Text color={tokens.dim}>{request.reason}</Text> : null}
      {request.arguments ? <Text color={tokens.dim}>{compactText(safeJson(request.arguments), 120)}</Text> : null}
      <Text color={tokens.dim}>Type /approve or /deny.</Text>
    </Box>
  );
}
