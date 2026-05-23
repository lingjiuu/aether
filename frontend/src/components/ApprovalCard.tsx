import React from 'react';
import { Box, Text, useInput } from 'ink';
import { useAppState } from '../state/store.js';
import { safeJson } from '../utils/json.js';
import { compactText } from '../utils/format.js';
import { tokens } from '../theme/tokens.js';

type ApprovalChoice = {
  label: string;
  approved: boolean;
};

const choices: ApprovalChoice[] = [
  { label: 'Approve', approved: true },
  { label: 'Deny', approved: false },
];

export function ApprovalCard({ onRespond }: { onRespond: (approved: boolean) => void }) {
  const approval = useAppState().pendingApproval;
  const [selectedIndex, setSelectedIndex] = React.useState(0);

  React.useEffect(() => {
    setSelectedIndex(0);
  }, [approval?.request.approvalId]);

  useInput(
    (_input, key) => {
      if (!approval) {
        return;
      }
      if (key.upArrow || key.downArrow) {
        setSelectedIndex(current => (current === 0 ? 1 : 0));
        return;
      }
      if (key.return) {
        onRespond(choices[selectedIndex]?.approved ?? false);
      }
    },
    { isActive: Boolean(approval) },
  );

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
      <Box flexDirection="column" marginTop={1}>
        {choices.map((choice, index) => (
          <Box key={choice.label} flexDirection="row">
            <Text color={index === selectedIndex ? tokens.accent : tokens.dim}>
              {index === selectedIndex ? '› ' : '  '}
              {choice.label}
            </Text>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
