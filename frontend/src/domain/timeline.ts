import type { UiItemKind, UiToolCall, UiToolResult, UiToolUpdate } from '../protocol/wire.js';

export type TimelineStatus = 'RUNNING' | 'COMPLETED' | 'ABORTED' | 'ERROR' | 'SKIPPED';

export type TimelineItem = {
  id: string;
  kind: UiItemKind;
  status: TimelineStatus | string;
  contentIndex?: number | null;
  text: string;
  toolCall?: UiToolCall;
  toolUpdate?: UiToolUpdate;
  toolResult?: UiToolResult;
};

export type TimelineTurn = {
  turnId: string;
  commandId?: string | null;
  turn?: number | null;
  status: TimelineStatus | string;
  startedAtMs?: number | null;
  endedAtMs?: number | null;
  items: TimelineItem[];
};

export function isVisibleTimelineItem(item: TimelineItem): boolean {
  return item.kind !== 'CONTEXT_MESSAGE' || !isInternalContextText(item.text);
}

function isInternalContextText(text: string): boolean {
  const trimmed = text.trimStart();
  return trimmed.startsWith('Environment context:')
    || trimmed.startsWith('Environment context update:')
    || trimmed.startsWith('<turn_aborted>')
    || trimmed.startsWith('<skill>');
}
