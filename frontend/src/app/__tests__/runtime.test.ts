import { describe, expect, it, vi } from 'vitest';
import type { AetherClient } from '../../backend/AetherClient.js';
import { initialState, type AppAction } from '../../state/reducer.js';
import { boot, handleInput } from '../runtime.js';

describe('runtime boot', () => {
  it('does not surface normal Java interruption stack traces as notices', async () => {
    let stderrHandler: ((text: string) => void) | undefined;
    const dispatch = vi.fn<(action: AppAction) => void>();
    const client = {
      start: vi.fn(),
      onEvent: vi.fn(() => () => {}),
      onStderr: vi.fn(handler => {
        stderrHandler = handler;
        return () => {};
      }),
      initialize: vi.fn().mockResolvedValue({
        protocolVersion: 'test',
        sessionId: 'session-1',
        session: { sessionId: 'session-1', status: 'IDLE' },
      }),
      initialized: vi.fn().mockResolvedValue({ ok: true }),
    } as unknown as AetherClient;

    await boot(client, dispatch);
    stderrHandler?.('Exception in thread "aether-regular-turn-1"\njava.lang.InterruptedException\n        at java.base/java.lang.VirtualThread.sleepNanos(VirtualThread.java:782)\n');

    expect(dispatch).not.toHaveBeenCalledWith(expect.objectContaining({
      type: 'notice',
    }));
  });

  it('records rejected direct resume commands without surfacing footer notices', async () => {
    const dispatch = vi.fn<(action: AppAction) => void>();
    const client = {
      resume: vi.fn().mockResolvedValue({
        accepted: false,
        message: 'Cannot resume session abc: Model provider "old" is not configured.',
      }),
      currentSession: vi.fn().mockResolvedValue({ sessionId: 'session-1', status: 'IDLE' }),
    } as unknown as AetherClient;

    await handleInput('/resume abc', initialState, client, dispatch, vi.fn());

    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({
      type: 'localCommandCompleted',
      command: '/resume abc',
      output: 'Cannot resume session abc: Model provider "old" is not configured.',
    }));
    expect(dispatch).not.toHaveBeenCalledWith(expect.objectContaining({
      type: 'notice',
    }));
  });
});
