import { initialState, reducer } from '../../state/reducer.js';
import type { AppState } from '../../state/reducer.js';
import { createTerminalPresentation } from '../render/presentationModel.js';
import { flattenLines } from '../render/renderPrimitives.js';
import { renderScrollback } from '../render/renderScrollback.js';
import type { RenderBlock, TerminalView } from '../render/viewModel.js';
import { stripAnsi } from '../shared/text.js';

export function renderView(
  state: AppState,
  options: {
    columns?: number;
    rows?: number;
    composerCursorOffset?: number;
    transcriptScrollTop?: number;
    approvalSelectedIndex?: number;
  } = {},
) {
  return renderScrollback({
    presentation: createTerminalPresentation(state),
    columns: options.columns ?? 80,
    rows: options.rows ?? 24,
    composerCursorOffset: options.composerCursorOffset ?? 0,
    transcriptScrollTop: options.transcriptScrollTop,
    approvalSelectedIndex: options.approvalSelectedIndex,
  });
}

export function renderText(state: AppState, columns = 100): string {
  const view = renderView(state, { columns, rows: 40 });
  return viewLines(view.frame).map(stripAnsi).join('\n');
}

export function viewLines(block: RenderBlock): string[] {
  return flattenLines(block).map(line => line.text);
}

export function viewLineCount(block: RenderBlock): number {
  return flattenLines(block).length;
}

export function transcriptLines(view: TerminalView): string[] {
  return viewLines(view.transcript);
}

export function bottomLines(view: TerminalView): string[] {
  return viewLines(view.bottom);
}

export function longTranscriptState(count: number): AppState {
  return reducer(initialState, {
    type: 'history',
    history: {
      sessionId: 'session-1',
      turns: [
        {
          turnId: 'turn-1',
          status: 'COMPLETED',
          items: Array.from({ length: count }, (_, index) => ({
            id: `assistant-${index}`,
            kind: 'ASSISTANT_TEXT' as const,
            status: 'COMPLETED' as const,
            text: `message-${index}`,
          })),
        },
      ],
    },
  });
}

type ToolHistory = {
  id: string;
  toolCall: NonNullable<AppState['turns'][string]['items'][number]['toolCall']>;
  toolResult: NonNullable<AppState['turns'][string]['items'][number]['toolResult']>;
};

export function toolHistoryState({ id, toolCall, toolResult }: ToolHistory): AppState {
  return reducer(initialState, {
    type: 'history',
    history: {
      sessionId: 'session-1',
      turns: [
        {
          turnId: 'turn-1',
          status: 'COMPLETED',
          items: [
            {
              id,
              kind: 'TOOL_CALL',
              status: 'COMPLETED',
              toolCall,
              toolResult,
            },
          ],
        },
      ],
    },
  });
}
