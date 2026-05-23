import type { UiToolCall } from '../protocol/wire.js';

export function toolKey(toolCall: UiToolCall | undefined | null, fallback: string): string {
  return [
    toolCall?.itemId ?? fallback,
    toolCall?.contentIndex ?? '',
    toolCall?.toolCallId ?? '',
  ].join(':');
}
