import { describe, expect, it } from 'vitest';
import { renderMarkdownLines } from '../render/markdown.js';
import { stripAnsi } from '../shared/text.js';

describe('markdown renderer', () => {
  it('renders headings and inline markdown instead of showing source markers', () => {
    const lines = renderMarkdownLines('# Title\n\nThis is **bold** and `code`.', 80);
    const raw = lines.map(line => line.raw).join('\n');
    const styled = lines.map(line => line.text).join('\n');

    expect(raw).toContain('Title');
    expect(raw).toContain('This is bold and code.');
    expect(raw).not.toContain('# Title');
    expect(raw).not.toContain('**bold**');
    expect(raw).not.toContain('`code`');
    expect(styled).toContain('\x1b[1m');
    expect(styled).toContain('\x1b[2mcode');
  });

  it('renders blockquotes and ordered lists with terminal-friendly markers', () => {
    const lines = renderMarkdownLines('> quoted text\n\n1. first\n2. second', 80);
    const raw = lines.map(line => line.raw).join('\n');

    expect(raw).toContain('│ quoted text');
    expect(raw).toContain('1. first');
    expect(raw).toContain('2. second');
    expect(raw).not.toContain('> quoted text');
  });

  it('renders markdown tables as aligned terminal tables', () => {
    const lines = renderMarkdownLines('| Name | Value |\n|---|---:|\n| alpha | 10 |\n| beta | 200 |', 80);
    const rawLines = lines.map(line => stripAnsi(line.raw));

    expect(rawLines[0]).toBe('| Name  | Value |');
    expect(rawLines[1]).toBe('|-------|-------|');
    expect(rawLines[2]).toBe('| alpha |    10 |');
    expect(rawLines[3]).toBe('| beta  |   200 |');
  });

  it('does not leak trailing incomplete fence fragments while streaming', () => {
    const lines = renderMarkdownLines('```python\nprint("hi")\n``', 80);
    const raw = lines.map(line => line.raw).join('\n');

    expect(raw).toContain('print("hi")');
    expect(raw).not.toContain('``');
  });
});
