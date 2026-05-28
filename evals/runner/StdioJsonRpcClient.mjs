import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { createInterface } from 'node:readline';

export class StdioJsonRpcClient {
  constructor(options) {
    this.options = options;
    this.events = new EventEmitter();
    this.pending = new Map();
    this.nextId = 1;
    this.child = undefined;
    this.output = undefined;
    this.closed = false;
  }

  start() {
    if (this.child) {
      return;
    }
    const env = {
      ...process.env,
      ...(this.options.env || {}),
    };
    this.child = spawn(this.options.command, this.options.args || [], {
      cwd: this.options.cwd || process.cwd(),
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    this.output = createInterface({ input: this.child.stdout });
    this.output.on('line', line => this.#handleLine(line));

    this.child.stderr.on('data', chunk => {
      this.events.emit('stderr', String(chunk));
    });

    this.child.on('error', error => this.#failAll(error));
    this.child.on('exit', (code, signal) => {
      this.closed = true;
      this.#failAll(new Error(`backend exited with code ${code ?? 'null'} signal ${signal ?? 'null'}`));
      this.events.emit('exit', { code, signal });
    });
  }

  onNotification(handler) {
    this.events.on('notification', handler);
    return () => this.events.off('notification', handler);
  }

  onStderr(handler) {
    this.events.on('stderr', handler);
    return () => this.events.off('stderr', handler);
  }

  onExit(handler) {
    this.events.on('exit', handler);
    return () => this.events.off('exit', handler);
  }

  request(method, params) {
    if (!this.child) {
      this.start();
    }
    if (!this.child || this.closed) {
      return Promise.reject(new Error('backend is not running'));
    }

    const id = String(this.nextId++);
    const message = params === undefined ? { id, method } : { id, method, params };
    const payload = JSON.stringify(message) + '\n';

    const response = new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
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

  close() {
    this.closed = true;
    this.output?.close();
    this.child?.stdin.end();
    this.child?.kill();
    this.#failAll(new Error('transport closed'));
  }

  #handleLine(line) {
    if (!line.trim()) {
      return;
    }

    let message;
    try {
      message = JSON.parse(line);
    } catch {
      this.events.emit('stderr', `Ignoring non-JSON backend output: ${line}\n`);
      return;
    }

    if (!message || typeof message !== 'object') {
      return;
    }

    if ('method' in message && !('id' in message)) {
      this.events.emit('notification', message);
      return;
    }

    const id = String(message.id ?? '');
    const pending = this.pending.get(id);
    if (!pending) {
      return;
    }
    this.pending.delete(id);

    if (message.error) {
      const error = new Error(message.error.message || 'JSON-RPC error');
      error.code = message.error.code;
      error.data = message.error.data;
      pending.reject(error);
      return;
    }
    pending.resolve(message.result);
  }

  #failAll(error) {
    for (const pending of this.pending.values()) {
      pending.reject(error);
    }
    this.pending.clear();
  }
}
