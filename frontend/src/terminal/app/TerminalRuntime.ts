import { ansi } from '../shared/ansi.js';

type TerminalRuntimeOptions = {
  stdin: NodeJS.ReadStream;
  stdout: NodeJS.WriteStream;
  onData: (chunk: string | Buffer) => void;
  onResize: () => void;
  onStop: () => void;
  onExit: () => void;
};

export class TerminalRuntime {
  private readonly stdin: NodeJS.ReadStream;
  private readonly stdout: NodeJS.WriteStream;
  private readonly onData: (chunk: string | Buffer) => void;
  private readonly onResize: () => void;
  private readonly onStop: () => void;
  private readonly onExit: () => void;
  private terminalModesEnabled = false;
  private started = false;

  constructor({ stdin, stdout, onData, onResize, onStop, onExit }: TerminalRuntimeOptions) {
    this.stdin = stdin;
    this.stdout = stdout;
    this.onData = onData;
    this.onResize = onResize;
    this.onStop = onStop;
    this.onExit = onExit;
  }

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.stdin.setEncoding('utf8');
    if (this.stdin.isTTY) {
      this.stdin.setRawMode(true);
    }
    this.enableTerminalModes();
    this.stdin.resume();
    this.stdin.on('data', this.onData);
    this.stdout.on('resize', this.onResize);
    process.once('SIGINT', this.onStop);
    process.once('SIGTERM', this.onStop);
    process.once('exit', this.handleExit);
  }

  stop(): void {
    if (!this.started) {
      return;
    }
    this.started = false;
    this.stdin.off('data', this.onData);
    this.stdout.off('resize', this.onResize);
    process.off('SIGINT', this.onStop);
    process.off('SIGTERM', this.onStop);
    process.off('exit', this.handleExit);
    this.disableTerminalModes();
    if (this.stdin.isTTY) {
      this.stdin.setRawMode(false);
    }
    this.stdin.pause();
  }

  private readonly handleExit = (): void => {
    this.disableTerminalModes();
    this.onExit();
  };

  private enableTerminalModes(): void {
    if (this.terminalModesEnabled || !this.stdout.isTTY) {
      return;
    }
    this.stdout.write(ansi.enableMouseTracking);
    this.terminalModesEnabled = true;
  }

  private disableTerminalModes(): void {
    if (!this.terminalModesEnabled) {
      return;
    }
    this.stdout.write(ansi.disableMouseTracking);
    this.terminalModesEnabled = false;
  }
}
