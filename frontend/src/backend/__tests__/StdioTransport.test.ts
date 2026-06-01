import { describe, expect, it } from 'vitest';
import { StdioTransport } from '../StdioTransport.js';

describe('StdioTransport', () => {
  it('includes backend command, cwd, exit status, and stderr on startup failures', async () => {
    const transport = new StdioTransport({
      command: process.execPath,
      args: ['-e', "process.stderr.write('backend boom\\n'); setTimeout(() => process.exit(42), 20);"],
      cwd: process.cwd(),
      sessionCwd: process.cwd(),
    });

    await expect(transport.request('initialize')).rejects.toThrow(
      /Aether backend failed to start\.[\s\S]*command:[\s\S]*cwd:[\s\S]*exit: code=42[\s\S]*backend boom/,
    );
  });
});
