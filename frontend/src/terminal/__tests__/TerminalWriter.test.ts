import { describe, expect, it } from 'vitest';
import { TerminalWriter } from '../TerminalWriter.js';

describe('TerminalWriter', () => {
  it('renders on the main screen without entering alternate screen', () => {
    const chunks: string[] = [];
    const stdout = {
      write(chunk: string) {
        chunks.push(String(chunk));
        return true;
      },
    } as NodeJS.WriteStream;
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'width:79',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    });

    const output = chunks.join('');
    expect(output).not.toContain('\x1b[?1049h');
    expect(output).toContain('Welcome back!');
    expect(output).toContain('❯ ');
  });

  it('patches same-height live updates without printing newlines', () => {
    const chunks: string[] = [];
    const stdout = {
      write(chunk: string) {
        chunks.push(String(chunk));
        return true;
      },
    } as NodeJS.WriteStream;
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'width:79',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['─'.repeat(79), '❯ ', '─'.repeat(79), '? for shortcuts'],
      cursor: { x: 2, y: 1 },
    });
    writer.render({
      resetKey: 'width:79',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['─'.repeat(79), '❯ 你', '─'.repeat(79), '? for shortcuts'],
      cursor: { x: 4, y: 1 },
    });

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).not.toContain('\r\n');
    expect(updateOutput).toContain('\x1b[K');
    expect(updateOutput).toContain('❯ 你');
  });
});
