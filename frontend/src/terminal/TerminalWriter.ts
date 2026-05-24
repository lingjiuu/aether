import { ansi, cursorMove } from './ansi.js';
import type { TerminalSection, TerminalView } from './renderScrollback.js';
import { visualWidth } from './text.js';

export class TerminalWriter {
  private started = false;
  private resetKey?: string;
  private committedSectionKeys = new Set<string>();
  private liveHeight = 0;
  private liveLines: string[] = [];
  private displayCursor = { x: 0, y: 0 };
  private lastLiveSignature = '';

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
      output += this.resetFor(view.resetKey);
    }

    const pendingSections = view.sections.filter(section => !this.committedSectionKeys.has(section.key));
    const liveSignature = signatureForLive(view);
    if (!pendingSections.length && liveSignature === this.lastLiveSignature) {
      return;
    }

    const nextLiveHeight = liveLinesFor(view).length;
    const anchorShrinkingLiveBlock = !pendingSections.length && this.liveHeight > nextLiveHeight;
    const previousLiveHeight = this.liveHeight;
    const canPatchLiveBlock = !pendingSections.length && this.canPatchLiveBlock(view);

    output += ansi.hideCursor;
    if (canPatchLiveBlock) {
      output += this.patchLiveBlock(view);
    } else {
      output += this.clearLiveBlock();
      if (anchorShrinkingLiveBlock) {
        output += cursorMove(0, previousLiveHeight - nextLiveHeight);
      }
      for (const section of pendingSections) {
        output += writeCommittedLines(section.lines);
        this.committedSectionKeys.add(section.key);
      }
      output += this.renderLiveBlock(view);
    }
    output += ansi.showCursor;
    this.lastLiveSignature = liveSignature;

    this.stdout.write(`${ansi.syncStart}${output}${ansi.syncEnd}`);
  }

  reset(): void {
    if (!this.started) {
      return;
    }
    this.stdout.write(`${ansi.syncStart}${ansi.hideCursor}${this.resetFor(this.resetKey)}${ansi.showCursor}${ansi.syncEnd}`);
  }

  stop(): void {
    if (!this.started) {
      return;
    }
    this.started = false;
    const output = `${ansi.hideCursor}${this.clearLiveBlock()}${ansi.showCursor}`;
    this.stdout.write(`${ansi.syncStart}${output}${ansi.syncEnd}`);
  }

  private resetFor(resetKey: string | undefined): string {
    this.resetKey = resetKey;
    this.committedSectionKeys = new Set<string>();
    this.lastLiveSignature = '';
    const clearLive = this.clearLiveBlock();
    this.liveHeight = 0;
    this.liveLines = [];
    this.displayCursor = { x: 0, y: 0 };
    return `${clearLive}${ansi.clearVisible}${ansi.home}`;
  }

  private clearLiveBlock(): string {
    if (this.liveHeight <= 0) {
      return '';
    }
    const moveToTop = this.displayCursor.y > 0 ? cursorMove(0, -this.displayCursor.y) : '';
    this.liveHeight = 0;
    this.liveLines = [];
    this.displayCursor = { x: 0, y: 0 };
    return `\r${moveToTop}${ansi.eraseDown}`;
  }

  private renderLiveBlock(view: TerminalView): string {
    const lines = liveLinesFor(view);
    const body = lines.join('\r\n');
    const end = {
      x: visualWidth(lines.at(-1) ?? ''),
      y: lines.length - 1,
    };
    const cursor = view.cursor ?? end;
    this.liveHeight = lines.length;
    this.liveLines = lines;
    this.displayCursor = cursor;
    return `${body}${cursorMove(cursor.x - end.x, cursor.y - end.y)}`;
  }

  private canPatchLiveBlock(view: TerminalView): boolean {
    const nextLines = liveLinesFor(view);
    return this.liveHeight > 0 && nextLines.length === this.liveHeight;
  }

  private patchLiveBlock(view: TerminalView): string {
    const nextLines = liveLinesFor(view);
    let output = `\r${cursorMove(0, -this.displayCursor.y)}`;
    let current = { x: 0, y: 0 };

    for (const [index, nextLine] of nextLines.entries()) {
      if (this.liveLines[index] !== nextLine) {
        output += cursorMove(-current.x, index - current.y);
        current = { x: 0, y: index };
        output += nextLine;
        current = { x: visualWidth(nextLine), y: index };
        output += ansi.eraseLineRight;
      }
    }

    const end = {
      x: visualWidth(nextLines.at(-1) ?? ''),
      y: nextLines.length - 1,
    };
    const cursor = view.cursor ?? end;
    output += cursorMove(cursor.x - current.x, cursor.y - current.y);
    this.liveLines = nextLines;
    this.displayCursor = cursor;
    return output;
  }
}

function liveLinesFor(view: TerminalView): string[] {
  return view.liveLines.length ? view.liveLines : [''];
}

function writeCommittedLines(lines: string[]): string {
  return lines.map(line => `${line}\r\n`).join('');
}

function signatureForLive(view: TerminalView): string {
  return JSON.stringify({
    cursor: view.cursor ?? null,
    lines: view.liveLines,
  });
}
