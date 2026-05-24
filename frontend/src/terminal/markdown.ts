import { bold, dim } from './ansi.js';
import { visualWidth, wrapPlain } from './text.js';

export type MarkdownLine = {
  text: string;
  raw: string;
};

type MarkdownBlock =
  | { type: 'line'; line: string }
  | { type: 'code'; lines: string[] };

type Segment = {
  text: string;
  kind: 'plain' | 'code' | 'bold';
};

export function renderMarkdownLines(text: string, width: number): MarkdownLine[] {
  const blocks = compactBlocks(parseMarkdown(text));
  const lines: MarkdownLine[] = [];

  for (const [index, block] of blocks.entries()) {
    if (index > 0 && shouldSeparate(blocks[index - 1], block)) {
      lines.push({ text: '', raw: '' });
    }

    if (block.type === 'code') {
      lines.push(...renderCodeBlock(block.lines, width));
    } else {
      lines.push(...renderTextLine(block.line, width));
    }
  }

  return lines;
}

function renderTextLine(source: string, width: number): MarkdownLine[] {
  if (!source) {
    return [{ text: '', raw: '' }];
  }

  const raw = renderInlineRaw(source);
  const rendered = renderInlineStyled(source);
  if (visualWidth(raw) <= width) {
    return [{ text: rendered, raw }];
  }

  return wrapPlain(raw, width).map(line => ({ text: line, raw: line }));
}

function renderCodeBlock(sourceLines: string[], width: number): MarkdownLine[] {
  const lines = sourceLines.length ? sourceLines : [''];
  return lines.flatMap(sourceLine => {
    const raw = `  ${sourceLine}`;
    return wrapPlain(raw, width, '  ').map(wrapped => ({
      text: dim(wrapped),
      raw: wrapped,
    }));
  });
}

function parseMarkdown(text: string): MarkdownBlock[] {
  const blocks: MarkdownBlock[] = [];
  const lines = withoutTrailingIncompleteFence(text.split('\n'));
  let codeLines: string[] | null = null;
  let codeBlockKind: 'fenced' | 'indented' | null = null;

  for (const line of lines) {
    if (codeBlockKind === 'indented') {
      if (line.trim() === '') {
        codeLines?.push('');
        continue;
      }
      const unindented = stripCodeIndent(line);
      if (unindented !== null) {
        codeLines?.push(unindented);
        continue;
      }
      if (codeLines) {
        blocks.push({ type: 'code', lines: codeLines });
      }
      codeLines = null;
      codeBlockKind = null;
    }

    if (line.trimStart().startsWith('```')) {
      if (codeBlockKind === 'fenced' && codeLines) {
        blocks.push({ type: 'code', lines: codeLines });
        codeLines = null;
        codeBlockKind = null;
      } else {
        codeLines = [];
        codeBlockKind = 'fenced';
      }
      continue;
    }

    if (codeBlockKind === 'fenced') {
      codeLines?.push(line);
      continue;
    }

    const unindented = stripCodeIndent(line);
    if (unindented !== null) {
      codeLines = [unindented];
      codeBlockKind = 'indented';
      continue;
    }

    blocks.push({ type: 'line', line });
  }

  if (codeLines) {
    blocks.push({ type: 'code', lines: codeLines });
  }

  return blocks;
}

function withoutTrailingIncompleteFence(lines: string[]): string[] {
  const last = lines.at(-1);
  if (last !== undefined && /^`{1,2}$/.test(last.trim())) {
    return lines.slice(0, -1);
  }
  return lines;
}

function stripCodeIndent(line: string): string | null {
  if (line.startsWith('    ')) {
    return line.slice(4);
  }
  if (line.startsWith('\t')) {
    return line.slice(1);
  }
  return null;
}

function compactBlocks(blocks: MarkdownBlock[]): MarkdownBlock[] {
  const compacted: MarkdownBlock[] = [];

  for (const block of blocks) {
    if (block.type === 'code') {
      const previous = compacted.at(-1);
      if (previous?.type === 'line' && previous.line === '') {
        compacted.pop();
      }
      compacted.push({ type: 'code', lines: trimBlankEdges(block.lines) });
      continue;
    }

    const previous = compacted.at(-1);
    if (block.line === '' && previous?.type === 'line' && previous.line === '') {
      continue;
    }

    compacted.push(block);
  }

  const last = compacted.at(-1);
  if (last?.type === 'line' && last.line === '') {
    compacted.pop();
  }

  return compacted;
}

function trimBlankEdges(lines: string[]): string[] {
  let start = 0;
  let end = lines.length;

  while (start < end && lines[start]?.trim() === '') {
    start += 1;
  }

  while (end > start && lines[end - 1]?.trim() === '') {
    end -= 1;
  }

  return lines.slice(start, end);
}

function shouldSeparate(previous: MarkdownBlock | undefined, next: MarkdownBlock): boolean {
  return Boolean(previous && (previous.type === 'code' || next.type === 'code'));
}

function renderInlineRaw(line: string): string {
  return parseInline(line).map(segment => segment.text).join('');
}

function renderInlineStyled(line: string): string {
  return parseInline(line)
    .map(segment => {
      switch (segment.kind) {
        case 'bold':
          return bold(segment.text);
        case 'code':
          return dim(segment.text);
        case 'plain':
          return segment.text;
      }
    })
    .join('');
}

function parseInline(line: string): Segment[] {
  const segments: Segment[] = [];
  let index = 0;

  while (index < line.length) {
    const codeStart = line.indexOf('`', index);
    const boldStart = line.indexOf('**', index);
    const nextStart = nextInlineStart(codeStart, boldStart);
    if (nextStart === -1) {
      segments.push({ text: line.slice(index), kind: 'plain' });
      break;
    }

    if (nextStart > index) {
      segments.push({ text: line.slice(index, nextStart), kind: 'plain' });
    }

    if (nextStart === codeStart) {
      const end = line.indexOf('`', codeStart + 1);
      if (end === -1) {
        segments.push({ text: line.slice(codeStart + 1), kind: 'code' });
        break;
      }
      segments.push({ text: line.slice(codeStart + 1, end), kind: 'code' });
      index = end + 1;
    } else {
      const end = line.indexOf('**', boldStart + 2);
      if (end === -1) {
        segments.push({ text: line.slice(boldStart + 2), kind: 'bold' });
        break;
      }
      segments.push({ text: line.slice(boldStart + 2, end), kind: 'bold' });
      index = end + 2;
    }
  }

  return segments.filter(segment => segment.text.length > 0);
}

function nextInlineStart(codeStart: number, boldStart: number): number {
  if (codeStart === -1) {
    return boldStart;
  }
  if (boldStart === -1) {
    return codeStart;
  }
  return Math.min(codeStart, boldStart);
}
