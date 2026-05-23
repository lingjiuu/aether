import React from 'react';
import { renderToString } from 'ink';
import { describe, expect, it } from 'vitest';
import { MarkdownText } from '../MarkdownText.js';

describe('MarkdownText', () => {
  it('renders fenced code without fence markers', () => {
    const output = renderToString(<MarkdownText text={'```txt\nhello world\n```'} />);

    expect(output).toContain('hello world');
    expect(output).not.toContain('```');
    expect(output).not.toContain('txt');
  });

  it('renders fenced code compactly inside assistant text', () => {
    const output = renderToString(<MarkdownText text={'hello.txt 的内容是：\n\n```txt\nhello world\n```\n\n'} />);

    expect(output.split('\n')).toEqual(['hello.txt 的内容是：', '  hello world']);
  });

  it('renders indented code blocks compactly', () => {
    const output = renderToString(<MarkdownText text={'hello.txt 的内容是：\n\n    hello world\n\n'} />);

    expect(output.split('\n')).toEqual(['hello.txt 的内容是：', '  hello world']);
  });

  it('renders inline code without backticks', () => {
    const output = renderToString(<MarkdownText text={'Read `hello.txt` now'} />);

    expect(output).toContain('hello.txt');
    expect(output).not.toContain('`');
  });
});
