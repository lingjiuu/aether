import { isVisibleTimelineItem, type TimelineItem, type TimelineTurn } from '../../domain/timeline.js';
import type { LocalCommandEntry } from '../../state/reducer.js';
import { formatElapsedTime } from '../../utils/format.js';
import { bold, dim, error, success } from '../shared/ansi.js';
import { renderMarkdownLines } from './markdown.js';
import type { TerminalPresentation } from './presentationModel.js';
import { blankLine, commandLine, line, userMessageLine } from './renderPrimitives.js';
import { visualWidth, wrapPlain } from '../shared/text.js';
import {
  toolProgressView,
  toolResultView,
  toolUseView,
  type ToolLine,
  type ToolLineTone,
} from './toolRenderers.js';
import type { RenderedLine } from './viewModel.js';

export function renderTranscript(presentation: TerminalPresentation, width: number): RenderedLine[] {
  const transcript = renderHeader(presentation, width);

  appendLocalEntries(transcript, presentation.localCommandEntries, 0, width);
  for (const [index, turn] of presentation.turns.entries()) {
    transcript.push(...turnLines(turn, width));
    appendLocalEntries(transcript, presentation.localCommandEntries, index + 1, width);
  }

  return transcript;
}

export function renderTranscriptSections(presentation: TerminalPresentation, width: number): {
  history: RenderedLine[];
  active: RenderedLine[];
} {
  const history = renderHeader(presentation, width);
  const active: RenderedLine[] = [];
  let activeStarted = false;

  appendLocalEntries(history, presentation.localCommandEntries, 0, width);
  for (const [turnIndex, turn] of presentation.turns.entries()) {
    const targetForCompletedTurn = activeStarted ? active : history;
    if (isStableTurn(turn)) {
      targetForCompletedTurn.push(...turnLines(turn, width));
      appendLocalEntries(targetForCompletedTurn, presentation.localCommandEntries, turnIndex + 1, width);
      continue;
    }

    activeStarted = true;
    const splitIndex = firstUnstableItemIndex(turn);
    const stableItems = turn.items.slice(0, splitIndex);
    const activeItems = turn.items.slice(splitIndex);
    for (const item of stableItems.filter(isVisibleTimelineItem)) {
      history.push(...itemLines(item, width));
    }
    for (const item of activeItems.filter(isVisibleTimelineItem)) {
      active.push(...itemLines(item, width));
    }
    active.push(...turnStatusLines(turn, width));
    appendLocalEntries(active, presentation.localCommandEntries, turnIndex + 1, width);
  }

  return { history, active };
}

function renderHeader(presentation: TerminalPresentation, width: number): RenderedLine[] {
  const title = presentation.session.name?.trim() || 'Welcome back!';
  const statusParts = [presentation.session.model, presentation.session.cwd].filter(Boolean);
  const status = statusParts.join(' · ') || 'Aether';

  return [
    line(bold(title), title, width),
    line(dim(status), status, width),
    line(dim('Aether · Run /help to see commands'), 'Aether · Run /help to see commands', width),
    line(
      dim('Use /sessions to resume older work · Use /compact when context gets crowded'),
      'Use /sessions to resume older work · Use /compact when context gets crowded',
      width,
    ),
    blankLine(),
  ];
}

function isStableTurn(turn: TimelineTurn): boolean {
  return turn.status === 'COMPLETED' || turn.status === 'ABORTED';
}

function firstUnstableItemIndex(turn: TimelineTurn): number {
  const index = turn.items.findIndex(item => !isStableItem(item));
  return index === -1 ? turn.items.length : index;
}

function isStableItem(item: TimelineItem): boolean {
  if (item.kind === 'TOOL_CALL') {
    return Boolean(item.toolResult) || item.status === 'ABORTED' || item.status === 'ERROR' || item.status === 'SKIPPED';
  }
  return item.status === 'COMPLETED' || item.status === 'ABORTED' || item.status === 'ERROR' || item.status === 'SKIPPED';
}

function turnLines(turn: TimelineTurn, width: number): RenderedLine[] {
  const lines: RenderedLine[] = [];
  for (const item of turn.items.filter(isVisibleTimelineItem)) {
    lines.push(...itemLines(item, width));
  }
  lines.push(...turnStatusLines(turn, width));
  return lines;
}

function itemLines(item: TimelineItem, width: number): RenderedLine[] {
  switch (item.kind) {
    case 'USER_MESSAGE':
      return [blankLine(), userMessageLine(item.text, width)];
    case 'ASSISTANT_TEXT':
      return prefixedMarkdownWrapped('● ', item.text, width);
    case 'REASONING':
      return item.text ? [blankLine(), ...prefixedWrapped('✻ ', item.text, width, true)] : [];
    case 'TOOL_CALL':
      return toolCallLines(item, width);
    case 'TOOL_RESULT':
      return [];
    case 'CONTEXT_MESSAGE':
      return item.text ? [blankLine(), ...wrapPlain(item.text, width).map(text => line(dim(text), text, width))] : [];
  }
}

function toolCallLines(item: TimelineItem, width: number): RenderedLine[] {
  const toolUse = toolUseView(item);
  const isError = Boolean(item.toolResult?.error || item.status === 'ERROR' || item.status === 'FAILED' || item.status === 'ABORTED');
  const isRunning = item.status === 'RUNNING' || (!item.toolResult && !isError);
  const header = `● ${toolUse.name}${toolUse.summary ? `(${toolUse.summary})` : ''}`;
  const lines = [
    blankLine(),
    line(`${isError ? error('●') : '●'} ${bold(toolUse.name)}${toolUse.summary ? `(${toolUse.summary})` : ''}`, header, width),
  ];
  if (isRunning) {
    lines.push(...responseLines(toolProgressView(item).lines, width));
    return lines;
  }
  lines.push(...responseLines(toolResultView(item).lines, width));
  return lines;
}

function turnStatusLines(turn: TimelineTurn, width: number): RenderedLine[] {
  if (turn.status === 'RUNNING') {
    return [blankLine(), line(dim('✻ Working...'), '✻ Working...', width)];
  }
  if (turn.status !== 'COMPLETED' && turn.status !== 'ABORTED') {
    return [];
  }
  const duration = formatElapsedTime(turn.startedAtMs, turn.endedAtMs);
  const suffix = turn.status === 'ABORTED' ? 'interrupted' : duration ? `for ${duration}` : 'done';
  return [blankLine(), line(dim(`✻ Worked ${suffix}`), `✻ Worked ${suffix}`, width)];
}

function appendLocalEntries(
  target: RenderedLine[],
  entries: LocalCommandEntry[],
  position: number,
  width: number,
): void {
  for (const entry of entries.filter(localEntry => localEntry.afterTurnOrderLength === position)) {
    const lines = [blankLine(), commandLine(entry.command, width), ...entry.output.split('\n').map(outputLine => responseLine(outputLine, width))];
    target.push(...lines);
  }
}

function prefixedWrapped(prefix: string, text: string, width: number, muted = false): RenderedLine[] {
  const wrapped = wrapPlain(text, Math.max(1, width - visualWidth(prefix)), '  ');
  return [
    blankLine(),
    ...wrapped.map((content, index) => {
      const raw = `${index === 0 ? prefix : ' '.repeat(visualWidth(prefix))}${content}`;
      return line(muted ? dim(raw) : raw, raw, width);
    }),
  ];
}

function prefixedMarkdownWrapped(prefix: string, text: string, width: number): RenderedLine[] {
  const contentWidth = Math.max(1, width - visualWidth(prefix));
  const markdownLines = renderMarkdownLines(text, contentWidth);
  if (!markdownLines.length) {
    return [];
  }

  return [
    blankLine(),
    ...markdownLines.map((content, index) => {
      const currentPrefix = index === 0 ? prefix : ' '.repeat(visualWidth(prefix));
      return line(`${currentPrefix}${content.text}`, `${currentPrefix}${content.raw}`, width);
    }),
  ];
}

function responseLine(text: string, width: number, isError = false): RenderedLine {
  const raw = `  ⎿  ${text}`;
  return line(isError ? error(raw) : dim(raw), raw, width);
}

function responseLines(lines: ToolLine[], width: number): RenderedLine[] {
  return lines.length ? lines.map((toolLine, index) => responseBlockLine(toolLine, width, index === 0)) : [];
}

function responseBlockLine(toolLine: ToolLine, width: number, first: boolean): RenderedLine {
  const prefix = first ? '  ⎿  ' : '     ';
  const raw = `${prefix}${toolLine.text}`;
  return line(tone(raw, toolLine.tone), raw, width);
}

function tone(text: string, toneName: ToolLineTone | undefined): string {
  switch (toneName) {
    case 'error':
      return error(text);
    case 'success':
      return success(text);
    case 'normal':
      return text;
    case 'dim':
    default:
      return dim(text);
  }
}
