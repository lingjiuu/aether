import type { Key } from '../input/inputParser.js';
import { clampNumber } from '../shared/terminalMath.js';
import type { TerminalScrollInfo } from '../render/viewModel.js';

const DEFAULT_SCROLL: TerminalScrollInfo = {
  scrollTop: 0,
  maxScrollTop: 0,
  viewportRows: 1,
  transcriptLineCount: 0,
  isAtBottom: true,
  isSticky: true,
  pendingDeltaRows: 0,
};

export class TranscriptViewportController {
  private scrollTop: number | undefined;
  private pendingDeltaRows = 0;
  private lastScroll: TerminalScrollInfo = DEFAULT_SCROLL;

  get renderScrollTop(): number | undefined {
    return this.scrollTop;
  }

  get isSticky(): boolean {
    return this.scrollTop === undefined;
  }

  get pendingDelta(): number {
    return this.pendingDeltaRows;
  }

  resetToBottom(): void {
    this.scrollTop = undefined;
    this.pendingDeltaRows = 0;
  }

  sync(scroll: TerminalScrollInfo): void {
    this.lastScroll = scroll;
    if (this.scrollTop !== undefined) {
      this.scrollTop = scroll.isAtBottom ? undefined : scroll.scrollTop;
    }
    this.pendingDeltaRows = 0;
  }

  handleKey(key: Key, context: { composerHasValue: boolean; commandPanelOpen: boolean }): boolean {
    switch (key.kind) {
      case 'page-up':
        return this.scrollBy(-this.pageScrollRows());
      case 'page-down':
        return this.scrollBy(this.pageScrollRows());
      case 'home':
        return context.composerHasValue || context.commandPanelOpen ? false : this.scrollToTop();
      case 'end':
        return context.composerHasValue || context.commandPanelOpen ? false : this.scrollToBottom();
      case 'wheel-up':
        return this.scrollBy(-3);
      case 'wheel-down':
        return this.scrollBy(3);
      default:
        return false;
    }
  }

  private scrollBy(deltaRows: number): boolean {
    const maxScrollTop = this.lastScroll.maxScrollTop;
    if (maxScrollTop <= 0) {
      return false;
    }
    const currentTop = this.scrollTop ?? maxScrollTop;
    const nextTop = clampNumber(currentTop + deltaRows, 0, maxScrollTop);
    this.pendingDeltaRows = deltaRows;
    this.scrollTop = nextTop >= maxScrollTop ? undefined : nextTop;
    return true;
  }

  private scrollToTop(): boolean {
    if (this.lastScroll.maxScrollTop <= 0) {
      return false;
    }
    this.pendingDeltaRows = 0;
    this.scrollTop = 0;
    return true;
  }

  private scrollToBottom(): boolean {
    if (this.scrollTop === undefined) {
      return false;
    }
    this.pendingDeltaRows = 0;
    this.scrollTop = undefined;
    return true;
  }

  private pageScrollRows(): number {
    return Math.max(1, this.lastScroll.viewportRows - 1);
  }
}
