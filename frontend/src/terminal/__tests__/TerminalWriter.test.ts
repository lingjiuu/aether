import { describe, expect, it } from 'vitest';
import { TerminalWriter } from '../app/TerminalWriter.js';
import { block, line } from '../render/renderPrimitives.js';
import type { TerminalView } from '../render/viewModel.js';

describe('TerminalWriter', () => {
  it('renders on the main screen without entering alternate screen', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['Welcome back!', ''],
      activeLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    }));

    const output = chunks.join('');
    expect(output).not.toContain('\x1b[?1049h');
    expect(output).toContain('Welcome back!');
    expect(output).toContain('❯ ');
  });

  it('redraws the visible frame in place when the prompt changes', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['Welcome back!'],
      activeLines: ['─'.repeat(79), '❯ ', '─'.repeat(79), '? for shortcuts'],
      cursor: { x: 2, y: 2 },
    }));
    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['Welcome back!'],
      activeLines: ['─'.repeat(79), '❯ 你', '─'.repeat(79), '? for shortcuts'],
      cursor: { x: 4, y: 2 },
    }));

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('\x1b[J');
    expect(updateOutput).toContain('❯ 你');
  });

  it('appends new stable history without rewriting old history', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['tool 1', 'tool 2'],
      activeLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    }));
    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['tool 1', 'tool 2', 'tool 3'],
      activeLines: ['❯ hello'],
      cursor: { x: 7, y: 0 },
    }));

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('\x1b[J');
    expect(updateOutput).toContain('tool 3');
    expect(updateOutput).toContain('❯ hello');
    expect(updateOutput).not.toContain('tool 1');
    expect(updateOutput).not.toContain('tool 2');
  });

  it('skips identical frame renders', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);
    const view = terminalView({
      resetKey: 'transcript:0',
      historyLines: ['Welcome back!'],
      activeLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    });

    writer.render(view);
    writer.render(view);

    expect(chunks).toHaveLength(1);
  });

  it('clears terminal scrollback when committed history is replaced', () => {
    const chunks: string[] = [];
    const stdout = fakeStdout(chunks);
    const writer = new TerminalWriter(stdout);

    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['old history'],
      activeLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    }));
    writer.render(terminalView({
      resetKey: 'transcript:0',
      historyLines: ['new history'],
      activeLines: ['❯ '],
      cursor: { x: 2, y: 0 },
    }));

    const updateOutput = chunks.at(-1) ?? '';
    expect(updateOutput).toContain('\x1b[3J');
    expect(updateOutput).toContain('new history');
  });
});

function terminalView({
  resetKey,
  historyLines,
  activeLines,
  cursor,
}: {
  resetKey: string;
  historyLines: string[];
  activeLines: string[];
  cursor: TerminalView['cursor'];
}): TerminalView {
  const history = block(
    'history',
    historyLines.map((text, index) => line(text, text, 1000, `test-history-${index}`)),
  );
  const active = block(
    'active',
    activeLines.map((text, index) => line(text, text, 1000, `test-active-${index}`)),
  );

  return {
    resetKey,
    frame: block('terminal-frame', [history, active]),
    history,
    active,
    cursor,
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
