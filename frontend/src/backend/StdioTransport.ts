import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface, type Interface } from 'node:readline';
import { EventEmitter } from 'node:events';
import { newRequestId } from './requestIds.js';

type PendingRequest = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
};

export type JsonRpcNotification = {
  method?: string;
  params?: unknown;
};

export type StdioTransportOptions = {
  command: string;
  args: string[];
  cwd: string;
  env?: NodeJS.ProcessEnv;
};

export class StdioTransport {
  private readonly events = new EventEmitter();
  private readonly pending = new Map<string, PendingRequest>();
  private child?: ChildProcessWithoutNullStreams;
  private output?: Interface;
  private closed = false;

  constructor(private readonly options: StdioTransportOptions) {}

  start(): void {
    if (this.child) {
      return;
    }
    this.child = spawn(this.options.command, this.options.args, {
      cwd: this.options.cwd,
      env: { ...process.env, ...this.options.env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    this.output = createInterface({ input: this.child.stdout });
    this.output.on('line', line => this.handleLine(line));

    this.child.stderr.on('data', chunk => {
      this.events.emit('stderr', String(chunk));
    });

    this.child.on('error', error => this.failAll(error));
    this.child.on('exit', (code, signal) => {
      this.closed = true;
      this.failAll(new Error(`Aether backend exited with code ${code ?? 'null'} signal ${signal ?? 'null'}`));
      this.events.emit('exit', { code, signal });
    });
  }

  onNotification(handler: (message: JsonRpcNotification) => void): () => void {
    this.events.on('notification', handler);
    return () => this.events.off('notification', handler);
  }

  onStderr(handler: (text: string) => void): () => void {
    this.events.on('stderr', handler);
    return () => this.events.off('stderr', handler);
  }

  async request<T>(method: string, params?: unknown): Promise<T> {
    if (!this.child) {
      this.start();
    }
    if (!this.child || this.closed) {
      throw new Error('Aether backend is not running.');
    }

    const id = newRequestId();
    const message = params === undefined ? { id, method } : { id, method, params };
    const payload = JSON.stringify(message) + '\n';

    const response = new Promise<T>((resolve, reject) => {
      this.pending.set(id, {
        resolve: value => resolve(value as T),
        reject,
      });
    });

    this.child.stdin.write(payload, error => {
      if (error) {
        const pending = this.pending.get(id);
        this.pending.delete(id);
        pending?.reject(error);
      }
    });

    return response;
  }

  close(): void {
    this.closed = true;
    this.output?.close();
    this.child?.stdin.end();
    this.child?.kill();
    this.failAll(new Error('Aether transport closed.'));
  }

  private handleLine(line: string): void {
    if (!line.trim()) {
      return;
    }

    let message: unknown;
    try {
      message = JSON.parse(line);
    } catch {
      this.events.emit('stderr', `Ignoring non-JSON backend output: ${line}\n`);
      return;
    }

    if (!isObject(message)) {
      return;
    }

    if ('method' in message && !('id' in message)) {
      this.events.emit('notification', message as JsonRpcNotification);
      return;
    }

    const id = String(message.id ?? '');
    const pending = this.pending.get(id);
    if (!pending) {
      return;
    }
    this.pending.delete(id);

    if ('error' in message && message.error) {
      pending.reject(new Error(errorMessage(message.error)));
      return;
    }

    pending.resolve((message as { result?: unknown }).result);
  }

  private failAll(error: Error): void {
    for (const pending of this.pending.values()) {
      pending.reject(error);
    }
    this.pending.clear();
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function errorMessage(error: unknown): string {
  if (isObject(error) && typeof error.message === 'string') {
    return error.message;
  }
  return String(error);
}
