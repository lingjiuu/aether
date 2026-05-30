import type { UiToolResult } from '../../../protocol/wire.js';
import { formatDuration, tailLines } from '../../../utils/format.js';
import type { ToolResultView } from './types.js';
import { clean, displayText, effectiveDurationMs, stripToolProtocolTags } from './utils.js';

export function defaultToolResultView(result: UiToolResult): ToolResultView {
  const output = tailLines(displayText(result) ?? stripToolProtocolTags(result.text), 5);
  if (output.length) {
    return { lines: output.map(text => ({ text, tone: 'dim' })) };
  }

  const duration = formatDuration(effectiveDurationMs(result));
  return { lines: [{ text: `Done${duration ? ` ${duration}` : ''}`, tone: 'dim' }] };
}

export function defaultToolErrorView(result: UiToolResult): ToolResultView {
  const message = displayText(result) ?? clean(stripToolProtocolTags(result.text));
  const output = tailLines(message, 5);
  if (output.length) {
    return { lines: output.map(text => ({ text, tone: 'error' })) };
  }

  const duration = formatDuration(effectiveDurationMs(result));
  return { lines: [{ text: `Error${duration ? ` ${duration}` : ''}`, tone: 'error' }] };
}
