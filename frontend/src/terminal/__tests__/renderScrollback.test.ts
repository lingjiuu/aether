import { describe, expect, it } from 'vitest';
import { initialState } from '../../state/reducer.js';
import { viewLineCount, renderView } from './renderTestHelpers.js';

describe('renderScrollback', () => {
  it('keeps transcript and bottom rows within the terminal height', () => {
    const view = renderView(initialState, { rows: 8 });

    expect(viewLineCount(view.frame)).toBeLessThanOrEqual(8);
    expect(view.resetKey).toBe('transcript:0');
  });
});
