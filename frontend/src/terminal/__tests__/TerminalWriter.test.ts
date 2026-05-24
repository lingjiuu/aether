import { describe, expect, it } from 'vitest';
import { TerminalWriter } from '../TerminalWriter.js';

describe('TerminalWriter', () => {
  it('renders on the main screen without entering alternate screen', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'transcript:0',
      transcriptLines: ['Welcome back!', ''],
      bottomLines: ['❯ '],
      scroll: scrollInfo(),
      cursor: { x: 2, y: 2 },
    });

    const output = chunks.join('');
    expect(output).not.toContain('\x1b[?1049h');
    expect(output).toContain('Welcome back!');
    expect(output).toContain('❯ ');
  });

  it('redraws the visible frame in place when the prompt changes', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'transcript:0',
      transcriptLines: ['Welcome back!'],
      bottomLines: ['─'.repeat(79), '❯ ', '─'.repeat(79), '? for shortcuts'],
      scroll: scrollInfo(),
      cursor: { x: 2, y: 2 },
    });
    writer.render({
      resetKey: 'transcript:0',
      transcriptLines: ['Welcome back!'],
      bottomLines: ['─'.repeat(79), '❯ 你', '─'.repeat(79), '? for shortcuts'],
      scroll: scrollInfo(),
      cursor: { x: 4, y: 2 },
    });

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('\x1b[J');
    expect(updateOutput).toContain('❯ 你');
  });

  it('keeps the bottom pane pinned when transcript height changes', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render({
      resetKey: 'transcript:0',
      transcriptLines: ['tool 1', 'tool 2', 'tool 3'],
      bottomLines: ['─'.repeat(79), '❯ ', '─'.repeat(79), '? for shortcuts'],
      scroll: scrollInfo(),
      cursor: { x: 2, y: 4 },
    });
    writer.render({
      resetKey: 'transcript:0',
      transcriptLines: ['tool 3'],
      bottomLines: ['─'.repeat(79), '❯ hello', '─'.repeat(79), '? for shortcuts'],
      scroll: scrollInfo(),
      cursor: { x: 7, y: 2 },
    });

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('\x1b[J');
    expect(updateOutput).toContain('tool 3');
    expect(updateOutput).toContain('❯ hello');
    expect(updateOutput).not.toContain('tool 1');
  });

  it('skips identical frame renders', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);
    const view = {
      resetKey: 'transcript:0',
      transcriptLines: ['Welcome back!'],
      bottomLines: ['❯ '],
      scroll: scrollInfo(),
      cursor: { x: 2, y: 1 },
    };

    writer.render(view);
    writer.render(view);

    expect(chunks).toHaveLength(1);
  });
});

function scrollInfo() {
  return {
    scrollTop: 0,
    maxScrollTop: 0,
    viewportRows: 1,
    transcriptLineCount: 1,
    isAtBottom: true,
  };
}

function fakeStdout(chunks: string[]): NodeJS.WriteStream {
  return {
    write(chunk: string) {
      chunks.push(String(chunk));
      return true;
    },
  } as NodeJS.WriteStream;
}
