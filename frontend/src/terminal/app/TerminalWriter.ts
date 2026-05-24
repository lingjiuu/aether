import { ansi, cursorMove } from '../shared/ansi.js';
import { visualWidth } from '../shared/text.js';
import { flattenLines } from '../render/renderPrimitives.js';
import type { TerminalView } from '../render/viewModel.js';

export class TerminalWriter {
  private started = false;
  private resetKey?: string;
  private activeHeight = 0;
  private committedHistoryLines = 0;
  private committedHistoryText: string[] = [];
  private displayCursor = { x: 0, y: 0 };
  private lastActiveSignature = '';

  constructor(private readonly stdout: NodeJS.WriteStream = process.stdout) {}

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
  }

  render(view: TerminalView): void {
    if (!this.started) {
      this.start();
    }

    let output = '';
    if (this.resetKey === undefined) {
      this.resetKey = view.resetKey;
    } else if (this.resetKey !== view.resetKey) {
      output += this.resetFor(view.resetKey, true);
    }

    const historyLines = historyLinesFor(view);
    const activeLines = activeLinesFor(view);
    const activeSignature = signatureForActive(view, activeLines);
    const historyChanged = !this.committedHistoryPrefixMatches(historyLines);
    const hasNewHistory = historyLines.length > this.committedHistoryLines;
    if (!historyChanged && !hasNewHistory && activeSignature === this.lastActiveSignature) {
      return;
    }

    output += ansi.hideCursor;
    if (historyChanged) {
      output += this.resetFor(view.resetKey, true);
    } else {
      output += this.clearActive();
    }
    output += this.appendHistory(historyLines);
    output += this.renderActive(view, activeLines);
    output += ansi.showCursor;
    this.lastActiveSignature = activeSignature;

    this.stdout.write(`${ansi.syncStart}${output}${ansi.syncEnd}`);
  }

  reset(): void {
    if (!this.started) {
      return;
    }
    this.stdout.write(`${ansi.syncStart}${ansi.hideCursor}${this.resetFor(this.resetKey, true)}${ansi.showCursor}${ansi.syncEnd}`);
  }

  stop(): void {
    if (!this.started) {
      return;
    }
    this.started = false;
    const output = `${ansi.hideCursor}${this.clearActive()}${ansi.showCursor}`;
    this.stdout.write(`${ansi.syncStart}${output}${ansi.syncEnd}`);
  }

  private resetFor(resetKey: string | undefined, clearScrollback = false): string {
    this.resetKey = resetKey;
    this.lastActiveSignature = '';
    const clearActive = this.clearActive();
    this.activeHeight = 0;
    this.committedHistoryLines = 0;
    this.committedHistoryText = [];
    this.displayCursor = { x: 0, y: 0 };
    return `${clearActive}${ansi.clearVisible}${clearScrollback ? ansi.clearScrollback : ''}${ansi.home}`;
  }

  private clearActive(): string {
    if (this.activeHeight <= 0) {
      return '';
    }
    const moveToTop = this.displayCursor.y > 0 ? cursorMove(0, -this.displayCursor.y) : '';
    this.activeHeight = 0;
    this.displayCursor = { x: 0, y: 0 };
    return `\r${moveToTop}${ansi.eraseDown}`;
  }

  private appendHistory(historyLines: string[]): string {
    const newLines = historyLines.slice(this.committedHistoryLines);
    this.committedHistoryLines = historyLines.length;
    this.committedHistoryText = historyLines;
    if (!newLines.length) {
      return '';
    }
    return `${newLines.join('\r\n')}\r\n`;
  }

  private committedHistoryPrefixMatches(historyLines: string[]): boolean {
    if (this.committedHistoryLines > historyLines.length) {
      return false;
    }
    return this.committedHistoryText.every((line, index) => historyLines[index] === line);
  }

  private renderActive(view: TerminalView, lines: string[]): string {
    const body = lines.length ? lines.join('\r\n') : '';
    const end = {
      x: visualWidth(lines.at(-1) ?? ''),
      y: Math.max(0, lines.length - 1),
    };
    const cursor = view.cursor ?? end;
    this.activeHeight = lines.length;
    this.displayCursor = cursor;
    return `${body}${cursorMove(cursor.x - end.x, cursor.y - end.y)}`;
  }
}

function historyLinesFor(view: TerminalView): string[] {
  return flattenLines(view.history).map(line => line.text);
}

function activeLinesFor(view: TerminalView): string[] {
  const lines = flattenLines(view.active).map(line => line.text);
  return lines.length ? lines : [''];
}

function signatureForActive(view: TerminalView, lines: string[]): string {
  return JSON.stringify({
    cursor: view.cursor ?? null,
    lines,
  });
}
