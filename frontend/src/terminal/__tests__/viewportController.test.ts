import { describe, expect, it } from 'vitest';
import { TranscriptViewportController } from '../viewport/viewportController.js';

describe('TranscriptViewportController', () => {
  it('starts sticky to the bottom', () => {
    const viewport = new TranscriptViewportController();

    expect(viewport.renderScrollTop).toBeUndefined();
  });

  it('scrolls by page rows and clears back to sticky at the bottom', () => {
    const viewport = new TranscriptViewportController();
    viewport.sync(scrollInfo({ maxScrollTop: 20, viewportRows: 6, scrollTop: 20, isAtBottom: true }));

    expect(viewport.handleKey({ kind: 'page-up' }, emptyContext())).toBe(true);
    expect(viewport.renderScrollTop).toBe(15);
    viewport.sync(scrollInfo({ maxScrollTop: 20, viewportRows: 6, scrollTop: 15, isAtBottom: false }));

    expect(viewport.handleKey({ kind: 'page-down' }, emptyContext())).toBe(true);
    expect(viewport.renderScrollTop).toBeUndefined();
  });

  it('lets Home and End fall through while the composer has text', () => {
    const viewport = new TranscriptViewportController();
    viewport.sync(scrollInfo({ maxScrollTop: 20, viewportRows: 6, scrollTop: 20, isAtBottom: true }));

    expect(viewport.handleKey({ kind: 'home' }, { composerHasValue: true, commandPanelOpen: false })).toBe(false);
    expect(viewport.renderScrollTop).toBeUndefined();
  });

  it('uses Home and End as transcript navigation when input is empty', () => {
    const viewport = new TranscriptViewportController();
    viewport.sync(scrollInfo({ maxScrollTop: 20, viewportRows: 6, scrollTop: 20, isAtBottom: true }));

    expect(viewport.handleKey({ kind: 'home' }, emptyContext())).toBe(true);
    expect(viewport.renderScrollTop).toBe(0);
    viewport.sync(scrollInfo({ maxScrollTop: 20, viewportRows: 6, scrollTop: 0, isAtBottom: false }));

    expect(viewport.handleKey({ kind: 'end' }, emptyContext())).toBe(true);
    expect(viewport.renderScrollTop).toBeUndefined();
  });
});

function emptyContext() {
  return { composerHasValue: false, commandPanelOpen: false };
}

function scrollInfo(overrides: { maxScrollTop: number; viewportRows: number; scrollTop: number; isAtBottom: boolean }) {
  return {
    transcriptLineCount: overrides.maxScrollTop + overrides.viewportRows,
    isSticky: overrides.isAtBottom,
    pendingDeltaRows: 0,
    ...overrides,
  };
}
