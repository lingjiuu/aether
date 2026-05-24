import { ansi, cursorMove } from '../shared/ansi.js';
import { visualWidth } from '../shared/text.js';
import { flattenLines } from '../render/renderPrimitives.js';
import type { TerminalView } from '../render/viewModel.js';

export class TerminalWriter {
  private started = false;
  private resetKey?: string;
  private frameHeight = 0;
  private displayCursor = { x: 0, y: 0 };
  private lastFrameSignature = '';

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

    const frameSignature = signatureForFrame(view);
    if (frameSignature === this.lastFrameSignature) {
      return;
    }

    output += ansi.hideCursor;
    output += this.clearFrame();
    output += this.renderFrame(view);
    output += ansi.showCursor;
    this.lastFrameSignature = frameSignature;

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
    const output = `${ansi.hideCursor}${this.clearFrame()}${ansi.showCursor}`;
    this.stdout.write(`${ansi.syncStart}${output}${ansi.syncEnd}`);
  }

  private resetFor(resetKey: string | undefined): string {
    this.resetKey = resetKey;
    this.lastFrameSignature = '';
    const clearFrame = this.clearFrame();
    this.frameHeight = 0;
    this.displayCursor = { x: 0, y: 0 };
    return `${clearFrame}${ansi.clearVisible}${ansi.home}`;
  }

  private clearFrame(): string {
    if (this.frameHeight <= 0) {
      return '';
    }
    const moveToTop = this.displayCursor.y > 0 ? cursorMove(0, -this.displayCursor.y) : '';
    this.frameHeight = 0;
    this.displayCursor = { x: 0, y: 0 };
    return `\r${moveToTop}${ansi.eraseDown}`;
  }

  private renderFrame(view: TerminalView): string {
    const lines = frameLinesFor(view);
    const body = lines.join('\r\n');
    const end = {
      x: visualWidth(lines.at(-1) ?? ''),
      y: lines.length - 1,
    };
    const cursor = view.cursor ?? end;
    this.frameHeight = lines.length;
    this.displayCursor = cursor;
    return `${body}${cursorMove(cursor.x - end.x, cursor.y - end.y)}`;
  }
}

function frameLinesFor(view: TerminalView): string[] {
  const lines = flattenLines(view.frame).map(line => line.text);
  return lines.length ? lines : [''];
}

function signatureForFrame(view: TerminalView): string {
  return JSON.stringify({
    cursor: view.cursor ?? null,
    lines: frameLinesFor(view),
  });
}
