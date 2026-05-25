import { EventEmitter } from 'node:events';
import { describe, expect, it, vi } from 'vitest';
import type { AetherClient } from '../../backend/AetherClient.js';
import type { UiCommandAck, UiSessionState } from '../../protocol/wire.js';
import { TerminalApp } from '../app/TerminalApp.js';

describe('TerminalApp', () => {
  it('cancels a running turn when escape is pressed without an overlay', async () => {
    const stdin = fakeStdin();
    const client = fakeClient({ status: 'RUNNING' });
    const app = new TerminalApp(client, stdin, fakeStdout());

    await app.start();
    stdin.emit('data', '\x1b');
    await Promise.resolve();

    expect(client.cancelTurn).toHaveBeenCalledOnce();
    expect(client.currentSession).not.toHaveBeenCalled();
    app.stop();
  });

  it('lets slash command suggestions consume escape before interrupting', async () => {
    const stdin = fakeStdin();
    const client = fakeClient({ status: 'RUNNING' });
    const app = new TerminalApp(client, stdin, fakeStdout());

    await app.start();
    stdin.emit('data', '/');
    stdin.emit('data', '\x1b');
    await Promise.resolve();

    expect(client.cancelTurn).not.toHaveBeenCalled();
    app.stop();
  });
});

function fakeClient(session: UiSessionState): AetherClient {
  return {
    start: vi.fn(),
    onEvent: vi.fn(() => () => {}),
    onStderr: vi.fn(() => () => {}),
    initialize: vi.fn().mockResolvedValue({ protocolVersion: 'test', sessionId: 'session-1', session }),
    initialized: vi.fn().mockResolvedValue({ ok: true }),
    currentSession: vi.fn().mockResolvedValue({ ...session, status: 'IDLE' }),
    cancelTurn: vi.fn<() => Promise<UiCommandAck>>().mockResolvedValue({ accepted: true, message: 'cancel requested' }),
    close: vi.fn(),
  } as unknown as AetherClient;
}

function fakeStdin(): NodeJS.ReadStream {
  const input = new EventEmitter() as NodeJS.ReadStream;
  input.isTTY = false;
  input.setEncoding = vi.fn();
  input.resume = vi.fn();
  input.pause = vi.fn();
  return input;
}

function fakeStdout(): NodeJS.WriteStream {
  const output = new EventEmitter() as NodeJS.WriteStream;
  output.columns = 100;
  output.rows = 30;
  output.write = vi.fn(() => true) as unknown as NodeJS.WriteStream['write'];
  return output;
}
