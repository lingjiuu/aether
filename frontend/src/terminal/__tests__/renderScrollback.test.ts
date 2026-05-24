import { describe, expect, it } from 'vitest';
import { initialState } from '../../state/reducer.js';
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
});
