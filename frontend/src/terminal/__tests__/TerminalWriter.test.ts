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

  it('keeps the live block bottom anchored when it shrinks', () => {
    const chunks: string[] = [];
    const stdout = {
      write(chunk: string) {
        chunks.push(String(chunk));
        return true;
      },
    } as NodeJS.WriteStream;
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'transcript:0',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['suggestion', '─'.repeat(79), '❯ /h', '─'.repeat(79), '? for shortcuts'],
      cursor: { x: 4, y: 2 },
    });
    writer.render({
      resetKey: 'transcript:0',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['─'.repeat(79), '❯ hello', '─'.repeat(79), '? for shortcuts'],
      cursor: { x: 7, y: 1 },
    });

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('\x1b[1B');
    expect(updateOutput).toContain('❯ hello');
  });

  it('does not rewrite committed sections whose keys were already printed', () => {
    const chunks: string[] = [];
    const stdout = {
      write(chunk: string) {
        chunks.push(String(chunk));
        return true;
      },
    } as NodeJS.WriteStream;
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'transcript:0',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    });
    writer.render({
      resetKey: 'transcript:0',
      sections: [{ key: 'header', lines: ['Changed title', ''] }],
      liveLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    });

    expect(chunks.at(-1) ?? '').not.toContain('Changed title');
  });

  it('appends only unseen committed section keys', () => {
    const chunks: string[] = [];
    const stdout = {
      write(chunk: string) {
        chunks.push(String(chunk));
        return true;
      },
    } as NodeJS.WriteStream;
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'transcript:0',
      sections: [{ key: 'header', lines: ['Welcome back!', ''] }],
      liveLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    });
    writer.render({
      resetKey: 'transcript:0',
      sections: [
        { key: 'header', lines: ['Changed title', ''] },
        { key: 'turn:1', lines: ['❯ hi', '● hello'] },
      ],
      liveLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    });

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('❯ hi');
    expect(updateOutput).toContain('● hello');
    expect(updateOutput).not.toContain('Changed title');
  });
});
