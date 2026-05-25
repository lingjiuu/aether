export function selectableReasoningEfforts(efforts: readonly string[] | null | undefined): string[] {
  return (efforts ?? [])
    .filter(effort => effort.toUpperCase() !== 'NONE')
    .sort((left, right) => reasoningRank(left) - reasoningRank(right));
}

function reasoningRank(effort: string): number {
  switch (effort.toUpperCase()) {
    case 'MINIMAL':
      return 0;
    case 'LOW':
      return 1;
    case 'MEDIUM':
      return 2;
    case 'HIGH':
      return 3;
    case 'XHIGH':
      return 4;
    default:
      return 99;
  }
}
