import React from 'react';
import { Box, Text } from 'ink';
type Segment = {
  text: string;
  kind: 'plain' | 'code' | 'bold';
};

export function MarkdownText({ text }: { text: string }) {
  const blocks = compactBlocks(parseMarkdown(text));

  return (
    <Box flexDirection="column" flexShrink={1}>
      {blocks.map((block, index) => {
        if (block.type === 'code') {
          return (
            <Box key={index} flexDirection="column" marginLeft={2}>
              {block.lines.map((line, lineIndex) => (
                <Text key={lineIndex}>{line || ' '}</Text>
              ))}
            </Box>
          );
        }

        return (
          <Text key={index} wrap="wrap">
            {block.line ? renderInline(block.line, index) : ' '}
          </Text>
        );
      })}
    </Box>
  );
}

type MarkdownBlock = { type: 'line'; line: string } | { type: 'code'; lines: string[] };

function parseMarkdown(text: string): MarkdownBlock[] {
  const blocks: MarkdownBlock[] = [];
  const lines = text.split('\n');
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
      compacted.push({
        type: 'code',
        lines: trimBlankEdges(block.lines),
      });
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

function renderInline(line: string, keyPrefix: number): React.ReactNode[] {
  return parseInline(line).map((segment, index) => {
    const key = `${keyPrefix}-${index}`;
    switch (segment.kind) {
      case 'code':
        return <Text key={key}>{segment.text}</Text>;
      case 'bold':
        return (
          <Text key={key} bold>
            {segment.text}
          </Text>
        );
      case 'plain':
        return <Text key={key}>{segment.text}</Text>;
    }
  });
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
        segments.push({ text: line.slice(codeStart), kind: 'plain' });
        break;
      }
      segments.push({ text: line.slice(codeStart + 1, end), kind: 'code' });
      index = end + 1;
    } else {
      const end = line.indexOf('**', boldStart + 2);
      if (end === -1) {
        segments.push({ text: line.slice(boldStart), kind: 'plain' });
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
