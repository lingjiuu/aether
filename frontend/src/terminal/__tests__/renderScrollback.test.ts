import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import { stripAnsi } from '../shared/text.js';
import { activeLines, historyLines, longTranscriptState, renderView } from './renderTestHelpers.js';

describe('renderScrollback', () => {
  it('returns native scrollback history and active rows without viewport clipping', () => {
    const view = renderView(longTranscriptState(12), { rows: 8 });

    expect(historyLines(view).length).toBeGreaterThan(8);
    expect(activeLines(view).length).toBeGreaterThan(0);
    expect(view.resetKey).toBe('transcript:1');
  });

  it('keeps an empty session prompt in the active area', () => {
    const view = renderView(initialState, { rows: 8 });

    expect(historyLines(view).length).toBeGreaterThan(0);
    expect(activeLines(view).join('\n')).toContain('❯ ');
  });

  it('bounds long running assistant previews to the visible active area', () => {
    const state = reducer(initialState, {
      type: 'history',
      history: {
        sessionId: 'session-1',
        turns: [
          {
            turnId: 'turn-1',
            status: 'RUNNING',
            items: [
              {
                id: 'user-1',
                kind: 'USER_MESSAGE',
                status: 'COMPLETED',
                text: '写一个很长的 markdown',
              },
              {
                id: 'assistant-1',
                kind: 'ASSISTANT_TEXT',
                status: 'RUNNING',
                text: Array.from({ length: 40 }, (_, index) => `line-${index}`).join('\n'),
              },
            ],
          },
        ],
      },
    });

    const view = renderView(state, { columns: 100, rows: 12 });
    const lines = activeLines(view);
    const text = lines.map(stripAnsi).join('\n');

    expect(lines.length).toBeLessThanOrEqual(12);
    expect(text).toContain('...');
    expect(text).toContain('line-39');
    expect(text).not.toContain('line-0');
    expect(text).toContain('❯ ');
  });
});
