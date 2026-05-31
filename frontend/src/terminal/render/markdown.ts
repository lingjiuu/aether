import { marked, type Token, type Tokens } from 'marked';
import { ansi, bold, dim } from '../shared/ansi.js';
import { padPlain, visualWidth, wrapPlain } from '../shared/text.js';

export type MarkdownLine = {
  text: string;
  raw: string;
};

type InlineContent = {
  text: string;
  raw: string;
};

let markedConfigured = false;

export function renderMarkdownLines(text: string, width: number): MarkdownLine[] {
  configureMarked();
  const source = withoutTrailingIncompleteFence(text.split('\n')).join('\n');
  return compactLines(marked.lexer(source).flatMap(token => renderToken(token, width, 0)));
}

function configureMarked(): void {
  if (markedConfigured) {
    return;
  }
  markedConfigured = true;
  marked.use({
    tokenizer: {
      del() {
        return undefined;
      },
    },
  });
}

function renderToken(token: Token, width: number, listDepth: number): MarkdownLine[] {
  switch (token.type) {
    case 'space':
      return [{ text: '', raw: '' }];
    case 'paragraph':
      return renderWrappedInline(inlineContent(token.tokens ?? [{ type: 'text', raw: token.text, text: token.text } as Tokens.Text]), width);
    case 'heading':
      return renderHeading(token as Tokens.Heading, width);
    case 'code':
      return renderCodeBlock(token.text, width);
    case 'blockquote':
      return renderBlockquote(token as Tokens.Blockquote, width);
    case 'list':
      return renderList(token as Tokens.List, width, listDepth);
    case 'hr':
      return [{ text: dim('─'.repeat(Math.max(1, width))), raw: '─'.repeat(Math.max(1, width)) }];
    case 'table':
      return renderTable(token as Tokens.Table, width);
    case 'html':
    case 'def':
      return [];
    default:
      return 'raw' in token ? renderWrappedInline({ text: token.raw, raw: token.raw }, width) : [];
  }
}

function renderHeading(token: Tokens.Heading, width: number): MarkdownLine[] {
  const content = inlineContent(token.tokens ?? [{ type: 'text', raw: token.text, text: token.text } as Tokens.Text]);
  const lines = wrapPlain(content.raw, width);
  return lines.map(raw => {
    const styled = token.depth === 1 ? underline(bold(raw)) : bold(raw);
    return { text: styled, raw };
  });
}

function renderCodeBlock(text: string, width: number): MarkdownLine[] {
  const sourceLines = text.split('\n');
  const lines = sourceLines.length ? sourceLines : [''];
  return lines.flatMap(line => wrapPlain(line, width).map(raw => ({ text: raw, raw })));
}

function renderBlockquote(token: Tokens.Blockquote, width: number): MarkdownLine[] {
  const prefix = '│ ';
  const innerWidth = Math.max(1, width - visualWidth(prefix));
  const innerLines = compactLines(token.tokens.flatMap(inner => renderToken(inner, innerWidth, 0)));
  return innerLines.map(inner => {
    if (!inner.raw) {
      return { text: dim(prefix.trimEnd()), raw: prefix.trimEnd() };
    }
    return {
      text: `${dim(prefix)}${inner.text}`,
      raw: `${prefix}${inner.raw}`,
    };
  });
}

function renderList(token: Tokens.List, width: number, listDepth: number): MarkdownLine[] {
  return token.items.flatMap((item, index) => {
    const marker = token.ordered ? `${Number(token.start) + index}. ` : '- ';
    const indent = '  '.repeat(listDepth);
    const prefix = `${indent}${marker}`;
    const continuation = ' '.repeat(visualWidth(prefix));
    const content = item.tokens.flatMap(child => renderToken(child, Math.max(1, width - visualWidth(prefix)), listDepth + 1));
    const compacted = compactLines(content);

    if (!compacted.length) {
      return [{ text: prefix.trimEnd(), raw: prefix.trimEnd() }];
    }

    return compacted.flatMap((line, lineIndex) => {
      const currentPrefix = lineIndex === 0 ? prefix : continuation;
      return renderPrefixedLine(currentPrefix, line, width);
    });
  });
}

function renderTable(token: Tokens.Table, width: number): MarkdownLine[] {
  const header = token.header.map(cellContent);
  const rows = token.rows.map(row => row.map(cellContent));
  const columnCount = header.length;
  const widths = Array.from({ length: columnCount }, (_, index) => {
    const cells = [header[index], ...rows.map(row => row[index])].filter((cell): cell is InlineContent => Boolean(cell));
    return Math.max(3, ...cells.map(cell => visualWidth(cell.raw)));
  });
  const tableWidth = 1 + widths.reduce((sum, columnWidth) => sum + columnWidth + 3, 0);

  if (tableWidth > width) {
    return renderNarrowTable(header, rows, width);
  }

  const output: MarkdownLine[] = [];
  output.push(tableRow(header, widths, token.align));
  output.push({
    text: tableSeparator(widths),
    raw: tableSeparator(widths),
  });
  output.push(...rows.map(row => tableRow(row, widths, token.align)));
  return output;
}

function renderNarrowTable(header: InlineContent[], rows: InlineContent[][], width: number): MarkdownLine[] {
  return rows.flatMap((row, rowIndex) => {
    const lines = row.flatMap((cell, columnIndex) => {
      const label = header[columnIndex]?.raw ?? `Column ${columnIndex + 1}`;
      const prefix = `${label}: `;
      return wrapPlain(`${prefix}${cell.raw}`, width).map(raw => ({ text: raw, raw }));
    });
    return rowIndex === 0 ? lines : [{ text: '', raw: '' }, ...lines];
  });
}

function tableRow(cells: InlineContent[], widths: number[], aligns?: Tokens.Table['align']): MarkdownLine {
  const rendered = cells.map((cell, index) => padCell(cell.text, cell.raw, widths[index] ?? 3, aligns?.[index]));
  const raw = cells.map((cell, index) => padAligned(cell.raw, widths[index] ?? 3, aligns?.[index]));
  return {
    text: `| ${rendered.join(' | ')} |`,
    raw: `| ${raw.join(' | ')} |`,
  };
}

function tableSeparator(widths: number[]): string {
  return `|${widths.map(width => '-'.repeat(width + 2)).join('|')}|`;
}

function cellContent(cell: Tokens.TableCell): InlineContent {
  return inlineContent(cell.tokens ?? [{ type: 'text', raw: cell.text, text: cell.text } as Tokens.Text]);
}

function renderWrappedInline(content: InlineContent, width: number): MarkdownLine[] {
  if (!content.raw) {
    return [{ text: '', raw: '' }];
  }
  if (visualWidth(content.raw) <= width) {
    return [{ text: content.text, raw: content.raw }];
  }
  return wrapPlain(content.raw, width).map(raw => ({ text: raw, raw }));
}

function renderPrefixedLine(prefix: string, line: MarkdownLine, width: number): MarkdownLine[] {
  const raw = `${prefix}${line.raw}`;
  const text = `${prefix}${line.text}`;
  if (visualWidth(raw) <= width) {
    return [{ text, raw }];
  }
  return wrapPlain(raw, width, ' '.repeat(visualWidth(prefix))).map(wrapped => ({ text: wrapped, raw: wrapped }));
}

function inlineContent(tokens: Token[]): InlineContent {
  return tokens.reduce<InlineContent>((result, token) => {
    const next = inlineToken(token);
    return {
      text: `${result.text}${next.text}`,
      raw: `${result.raw}${next.raw}`,
    };
  }, { text: '', raw: '' });
}

function inlineToken(token: Token): InlineContent {
  switch (token.type) {
    case 'text':
      if ('tokens' in token && token.tokens?.length) {
        return inlineContent(token.tokens);
      }
      return { text: token.text, raw: token.text };
    case 'strong': {
      const content = inlineContent(token.tokens ?? []);
      return { text: bold(content.text), raw: content.raw };
    }
    case 'em': {
      const content = inlineContent(token.tokens ?? []);
      return { text: italic(content.text), raw: content.raw };
    }
    case 'codespan':
      return { text: dim(token.text), raw: token.text };
    case 'br':
      return { text: '\n', raw: '\n' };
    case 'link': {
      const label = inlineContent(token.tokens ?? []);
      const raw = label.raw && label.raw !== token.href ? `${label.raw} (${token.href})` : token.href;
      return { text: raw, raw };
    }
    case 'image':
      return { text: token.href, raw: token.href };
    case 'escape':
      return { text: token.text, raw: token.text };
    default:
      return 'raw' in token ? { text: token.raw, raw: token.raw } : { text: '', raw: '' };
  }
}

function padCell(text: string, raw: string, width: number, align?: Tokens.Table['align'][number]): string {
  const aligned = padAligned(raw, width, align);
  return `${text}${' '.repeat(Math.max(0, visualWidth(aligned) - visualWidth(raw)))}`;
}

function padAligned(text: string, width: number, align?: Tokens.Table['align'][number]): string {
  const padding = Math.max(0, width - visualWidth(text));
  if (align === 'right') {
    return `${' '.repeat(padding)}${text}`;
  }
  if (align === 'center') {
    const left = Math.floor(padding / 2);
    return `${' '.repeat(left)}${text}${' '.repeat(padding - left)}`;
  }
  return padPlain(text, width);
}

function compactLines(lines: MarkdownLine[]): MarkdownLine[] {
  const compacted: MarkdownLine[] = [];
  for (const line of lines) {
    const previous = compacted.at(-1);
    if (!line.raw && !previous?.raw) {
      continue;
    }
    compacted.push(line);
  }
  while (compacted.at(-1)?.raw === '') {
    compacted.pop();
  }
  return compacted;
}

function withoutTrailingIncompleteFence(lines: string[]): string[] {
  const last = lines.at(-1);
  if (last !== undefined && /^`{1,2}$/.test(last.trim())) {
    return lines.slice(0, -1);
  }
  return lines;
}

function italic(text: string): string {
  return `${ansi.italic}${text}${ansi.reset}`;
}

function underline(text: string): string {
  return `${ansi.underline}${text}${ansi.reset}`;
}
