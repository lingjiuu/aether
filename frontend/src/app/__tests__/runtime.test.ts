import { describe, expect, it, vi } from 'vitest';
import type { AetherClient } from '../../backend/AetherClient.js';
import type { AppAction } from '../../state/reducer.js';
import { boot } from '../runtime.js';

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
});
