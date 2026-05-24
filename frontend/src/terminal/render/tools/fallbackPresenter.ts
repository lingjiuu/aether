import type { UiToolResult } from '../../../protocol/wire.js';
import { formatDuration, tailLines } from '../../../utils/format.js';
import type { ToolResultView } from './types.js';

export function fallbackResultView(result: UiToolResult): ToolResultView {
  const output = tailLines(result.text, 5);
  if (output.length) {
    return { lines: output.map(text => ({ text, tone: result.error ? 'error' : 'dim' })) };
  }

  const duration = formatDuration(result.durationMs);
  const status = result.error ? 'Error' : 'Done';
  return { lines: [{ text: `${status}${duration ? ` ${duration}` : ''}`, tone: result.error ? 'error' : 'dim' }] };
}
