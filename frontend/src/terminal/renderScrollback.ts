import { getSlashCommandPlaceholder, getSlashCommandSuggestions } from '../commands/slashCommands.js';
import { isVisibleTimelineItem, type TimelineItem, type TimelineTurn } from '../domain/timeline.js';
import type { UiSessionSummary } from '../protocol/wire.js';
import type { AppState, LocalCommandEntry } from '../state/reducer.js';
import { selectIsRunning, selectTurns } from '../state/selectors.js';
import { formatDuration, formatElapsedTime, formatToolUseSummary, tailLines } from '../utils/format.js';
import { accent, bold, dim, error, userLine, warning } from './ansi.js';
import { renderMarkdownLines } from './markdown.js';
import { truncatePlain, visualWidth, wrapPlain } from './text.js';

export type TerminalSection = {
  key: string;
  lines: string[];
};

export type TerminalView = {
  resetKey: string;
  sections: TerminalSection[];
  liveLines: string[];
  cursor?: { x: number; y: number };
};

type RenderOptions = {
  state: AppState;
  columns: number;
  rows: number;
  composerCursorOffset: number;
  approvalSelectedIndex?: number;
};

type RenderedLine = {
  text: string;
  raw: string;
};

type RenderTarget =
  | { kind: 'stable'; sections: TerminalSection[] }
  | { kind: 'live'; lines: RenderedLine[] };

const PROMPT = '❯ ';
const RESUME_VISIBLE_COUNT = 8;

export function renderScrollback({
  state,
  columns,
  rows,
  composerCursorOffset,
  approvalSelectedIndex = 0,
}: RenderOptions): TerminalView {
  const width = Math.max(20, columns - 1);
  const stableSections: TerminalSection[] = [
    section('header', renderHeader(state, width)),
  ];
  const liveTranscript: RenderedLine[] = [];
  let target: RenderTarget = { kind: 'stable', sections: stableSections };

  appendLocalEntries(target, state.localCommandEntries, 0, width);
  for (const [index, turn] of selectTurns(state).entries()) {
    if (target.kind === 'stable' && isStableTurn(turn)) {
      target.sections.push(section(`turn:${turn.turnId}`, turnLines(turn, width)));
    } else {
      if (target.kind === 'stable') {
        target = { kind: 'live', lines: liveTranscript };
      }
      target.lines.push(...turnLines(turn, width));
    }
    appendLocalEntries(target, state.localCommandEntries, index + 1, width);
  }

  if (state.sessions.length) {
    const sessionLines = renderSessions(state.sessions, width);
    if (target.kind === 'stable') {
      target.sections.push(section('sessions', sessionLines));
    } else {
      target.lines.push(...sessionLines);
    }
  }

  const footer = renderFooter(state, width);
  const bottomBudget = Math.max(6, rows - liveTranscript.length - footer.length);
  const bottom = state.commandPanel
    ? renderCommandPanel(state, width, bottomBudget)
    : state.pendingApproval
      ? renderApprovalPanel(state, width, approvalSelectedIndex)
    : renderComposer(state, width, composerCursorOffset);
  const liveLines = [...liveTranscript, ...bottom.lines, ...footer];
  const cursor = bottom.cursor
    ? { x: bottom.cursor.x, y: liveTranscript.length + bottom.cursor.y }
    : undefined;

  return limitLiveBlock({
    resetKey: `width:${width}`,
    sections: stableSections,
    liveLines: liveLines.map(rendered => rendered.text),
    cursor,
  }, Math.max(6, rows - 1));
}

function section(key: string, lines: RenderedLine[]): TerminalSection {
  return { key, lines: lines.map(rendered => rendered.text) };
}

function renderHeader(state: AppState, width: number): RenderedLine[] {
  const title = state.session.name?.trim() || 'Welcome back!';
  const statusParts = [state.session.model, state.session.cwd].filter(Boolean);
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

function renderFooter(state: AppState, width: number): RenderedLine[] {
  const left = state.pendingApproval ? 'esc to deny' : selectIsRunning(state) ? 'esc to interrupt' : '? for shortcuts';
  const notice = state.notices.at(-1);
  const text = notice ? `${left}  ${notice}` : left;
  return [line(dim(text), text, width)];
}

function renderComposer(
  state: AppState,
  width: number,
  composerCursorOffset: number,
): { lines: RenderedLine[]; cursor?: { x: number; y: number } } {
  const value = state.composer.value;
  const suggestions = state.composer.commandPaletteOpen ? getSlashCommandSuggestions(value) : [];
  const selectedIndex = clampIndex(state.composer.selectedSuggestionIndex, suggestions.length);
  const usageWidth = Math.max(0, ...suggestions.map(command => command.usage.length));
  const lines: RenderedLine[] = [];

  if (suggestions.length) {
    for (const [index, command] of suggestions.entries()) {
      const marker = index === selectedIndex ? '› ' : '  ';
      const usage = `${marker}${command.usage.padEnd(usageWidth)}`;
      const raw = `${usage} ${command.description}`;
      const styledUsage = index === selectedIndex ? accent(usage) : dim(usage);
      lines.push(line(`${styledUsage} ${dim(command.description)}`, raw, width));
    }
  }

  lines.push(separator(width));
  const cursorOffset = clampNumber(composerCursorOffset, 0, value.length);
  const beforeCursor = value.slice(0, cursorOffset);
  const afterCursor = value.slice(cursorOffset);
  const placeholder = cursorOffset === value.length ? getSlashCommandPlaceholder(value) : undefined;
  const rawPromptLine = `${PROMPT}${value}${placeholder ? `[${placeholder}]` : ''}`;
  const renderedPromptLine = `${accent(PROMPT)}${beforeCursor}${placeholder ? dim(`[${placeholder}]`) : ''}${afterCursor}`;
  lines.push(line(renderedPromptLine, rawPromptLine, width));
  lines.push(separator(width));
  const cursorX = visualWidth(`${PROMPT}${beforeCursor}`);
  return { lines, cursor: { x: Math.min(cursorX, width - 1), y: lines.length - 2 } };
}

function renderCommandPanel(
  state: AppState,
  width: number,
  maxRows: number,
): { lines: RenderedLine[]; cursor?: { x: number; y: number } } {
  const panel = state.commandPanel;
  if (!panel) {
    return { lines: [], cursor: undefined };
  }

  const lines: RenderedLine[] = [commandLine(panel.command, width), separator(width), blankLine()];
  if (panel.kind === 'help') {
    lines.push(...renderHelpPanel(width));
    return { lines, cursor: undefined };
  }

  const filteredSessions = filterSessions(panel.sessions, panel.query);
  const selectedIndex = clampIndex(panel.selectedIndex, filteredSessions.length);
  lines.push(line(bold('Resume session'), 'Resume session', width));
  lines.push(blankLine());

  const searchText = panel.query || 'Search...';
  const innerWidth = Math.max(0, width - 4);
  const searchRaw = `│ ${searchText}${' '.repeat(Math.max(0, innerWidth - visualWidth(searchText)))} │`;
  lines.push(line(`╭${'─'.repeat(Math.max(0, width - 2))}╮`, `╭${'─'.repeat(Math.max(0, width - 2))}╮`, width));
  lines.push(line(`│ ${panel.query ? panel.query : dim('Search...')}${' '.repeat(Math.max(0, innerWidth - visualWidth(searchText)))} │`, searchRaw, width));
  lines.push(line(`╰${'─'.repeat(Math.max(0, width - 2))}╯`, `╰${'─'.repeat(Math.max(0, width - 2))}╯`, width));
  lines.push(blankLine());

  if (filteredSessions.length) {
    const fixedRows = 12;
    const visibleCount = Math.max(1, Math.min(RESUME_VISIBLE_COUNT, Math.floor((maxRows - fixedRows) / 2)));
    const visibleStart = Math.max(
      0,
      Math.min(Math.max(selectedIndex - Math.floor(visibleCount / 2), 0), Math.max(filteredSessions.length - visibleCount, 0)),
    );
    for (const [offset, session] of filteredSessions
      .slice(visibleStart, visibleStart + visibleCount)
      .entries()) {
      const index = visibleStart + offset;
      lines.push(...sessionLines(session, index === selectedIndex, width));
    }
  } else {
    const emptyText = panel.query ? 'No matching sessions' : 'No sessions found';
    lines.push(line(dim(emptyText), emptyText, width));
  }

  lines.push(blankLine());
  lines.push(line(dim('Up/Down to select · Enter to resume · Esc to cancel'), 'Up/Down to select · Enter to resume · Esc to cancel', width));
  return {
    lines,
    cursor: { x: Math.min(2 + visualWidth(panel.query), width - 3), y: 6 },
  };
}

function renderHelpPanel(width: number): RenderedLine[] {
  const commands = getSlashCommandSuggestions('/');
  const usageWidth = Math.max(0, ...commands.map(command => command.usage.length));
  const lines: RenderedLine[] = [
    line(`${bold('Help')}  ${dim('General')}  ${dim('Commands')}  ${dim('Custom commands')}`, 'Help  General  Commands  Custom commands', width),
    blankLine(),
    line(dim('Browse default commands'), 'Browse default commands', width),
    blankLine(),
  ];
  for (const command of commands) {
    const raw = `  ${command.usage.padEnd(usageWidth)}  ${command.description}`;
    lines.push(line(`  ${command.usage.padEnd(usageWidth)}  ${dim(command.description)}`, raw, width));
  }
  lines.push(blankLine());
  lines.push(line(dim('Esc to cancel'), 'Esc to cancel', width));
  return lines;
}

function renderSessions(sessions: UiSessionSummary[], width: number): RenderedLine[] {
  const lines: RenderedLine[] = [blankLine(), line(bold('Sessions'), 'Sessions', width)];
  for (const session of sessions.slice(0, 8)) {
    lines.push(...sessionLines(session, false, width));
  }
  return lines;
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
      return [blankLine(), commandLine(item.text, width)];
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
  const toolCall = item.toolCall;
  const name = toolCall?.displayName ?? toolCall?.toolName ?? 'tool';
  const isError = Boolean(item.toolResult?.error || item.status === 'ERROR' || item.status === 'FAILED' || item.status === 'ABORTED');
  const isRunning = item.status === 'RUNNING' || (!item.toolResult && !isError);
  const args =
    toolCall?.displaySummary ?? (isRunning ? '' : formatToolUseSummary(toolCall?.toolName ?? name, toolCall?.argumentsJson, 160));
  const lines = [blankLine(), line(`${isError ? error('●') : '●'} ${bold(name)}${args ? `(${args})` : ''}`, `● ${name}${args ? `(${args})` : ''}`, width)];
  if (isRunning) {
    lines.push(responseLine('Running...', width));
    return lines;
  }
  const result = item.toolResult;
  if (!result) {
    return lines;
  }
  const resultLines = tailLines(result.text, 5);
  if (resultLines.length) {
    for (const resultLine of resultLines) {
      lines.push(responseLine(resultLine, width, Boolean(result.error)));
    }
  } else {
    const duration = formatDuration(result.durationMs);
    const text = result.error ? 'Error' : 'Done';
    lines.push(responseLine(`${text}${duration ? ` ${duration}` : ''}`, width, Boolean(result.error)));
  }
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
  target: RenderTarget,
  entries: LocalCommandEntry[],
  position: number,
  width: number,
): void {
  for (const entry of entries.filter(localEntry => localEntry.afterTurnOrderLength === position)) {
    const lines = [blankLine(), commandLine(entry.command, width), responseLine(entry.output, width)];
    if (target.kind === 'stable') {
      target.sections.push(section(`local:${entry.id}`, lines));
    } else {
      target.lines.push(...lines);
    }
  }
}

function sessionLines(session: UiSessionSummary, selected: boolean, width: number): RenderedLine[] {
  const title = session.name?.trim() || session.preview?.trim() || session.sessionId || 'Untitled session';
  const details = [formatRelativeSessionTime(session.updatedAt ?? session.createdAt), basename(session.cwd), session.modelId]
    .filter(Boolean)
    .join(' · ');
  const marker = selected ? '❯ ' : '  ';
  return [
    line(`${selected ? accent(marker) : marker}${selected ? accent(title) : title}`, `${marker}${title}`, width),
    details ? line(dim(`    ${details}`), `    ${details}`, width) : blankLine(),
  ];
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

function renderApprovalPanel(
  state: AppState,
  width: number,
  selectedIndex: number,
): { lines: RenderedLine[]; cursor?: { x: number; y: number } } {
  const request = state.pendingApproval?.request;
  if (!request) {
    return { lines: [], cursor: undefined };
  }

  const toolName = request.toolName ?? 'tool';
  const risk = request.riskLevel ? `Risk: ${request.riskLevel}` : undefined;
  const reason = request.reason?.trim() ? `Reason: ${request.reason.trim()}` : undefined;
  const args = formatApprovalArguments(request.arguments);
  const choiceIndex = clampIndex(selectedIndex, 2);
  const lines: RenderedLine[] = [
    separator(width),
    blankLine(),
    line(warning(`Approval required: ${toolName}`), `Approval required: ${toolName}`, width),
  ];

  for (const detail of [risk, reason, args ? `Arguments: ${args}` : undefined].filter(Boolean)) {
    lines.push(...wrapPlain(detail ?? '', width).map(text => line(dim(text), text, width)));
  }

  lines.push(blankLine());
  const approve = approvalChoiceLine('Approve', choiceIndex === 0, width);
  const deny = approvalChoiceLine('Deny', choiceIndex === 1, width);
  const approveY = lines.length;
  lines.push(approve, deny, blankLine());
  lines.push(line(dim('Up/Down to select · Enter to confirm · Esc to deny'), 'Up/Down to select · Enter to confirm · Esc to deny', width));
  lines.push(separator(width));

  return { lines, cursor: { x: 0, y: approveY + choiceIndex } };
}

function approvalChoiceLine(label: string, selected: boolean, width: number): RenderedLine {
  const marker = selected ? '› ' : '  ';
  const raw = `${marker}${label}`;
  return line(selected ? accent(raw) : raw, raw, width);
}

function formatApprovalArguments(args?: Record<string, unknown> | null): string {
  if (!args || !Object.keys(args).length) {
    return '';
  }
  return truncatePlain(JSON.stringify(args), 160);
}

function responseLine(text: string, width: number, isError = false): RenderedLine {
  const raw = `  ⎿  ${text}`;
  return line(isError ? error(raw) : dim(raw), raw, width);
}

function commandLine(command: string, width: number): RenderedLine {
  const raw = `${PROMPT}${command}`;
  return line(userLine(`${PROMPT}${bold(command)}`), raw, width);
}

function separator(width: number): RenderedLine {
  return line(dim('─'.repeat(width)), '─'.repeat(width), width);
}

function blankLine(): RenderedLine {
  return line('', '', Number.MAX_SAFE_INTEGER);
}

function line(text: string, raw: string, width: number): RenderedLine {
  if (visualWidth(raw) > width) {
    const truncated = truncatePlain(raw, width);
    return { text: truncated, raw: truncated };
  }
  return { text, raw };
}

function isStableTurn(turn: TimelineTurn): boolean {
  return turn.status !== 'RUNNING' && turn.items.every(item => item.status !== 'RUNNING');
}

function limitLiveBlock(view: TerminalView, maxRows: number): TerminalView {
  if (view.liveLines.length <= maxRows) {
    return view;
  }
  const removed = view.liveLines.length - maxRows;
  const cursor = view.cursor && view.cursor.y >= removed
    ? { ...view.cursor, y: view.cursor.y - removed }
    : undefined;
  return {
    ...view,
    liveLines: view.liveLines.slice(removed),
    cursor,
  };
}

function filterSessions(sessions: UiSessionSummary[], query: string): UiSessionSummary[] {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return sessions;
  }

  return sessions.filter(session =>
    [session.name, session.preview, session.sessionId, session.cwd, session.modelId]
      .filter(Boolean)
      .some(value => value?.toLowerCase().includes(normalizedQuery)),
  );
}

function basename(path?: string | null): string {
  const normalized = path?.replace(/\/+$/, '');
  return normalized?.split('/').filter(Boolean).at(-1) ?? '';
}

function formatRelativeSessionTime(timestamp?: number | null): string {
  if (!timestamp) {
    return '';
  }

  const seconds = timestamp > 1_000_000_000_000 ? timestamp / 1000 : timestamp;
  const elapsed = Math.max(0, Math.floor(Date.now() / 1000 - seconds));
  if (elapsed < 60) {
    return 'just now';
  }
  if (elapsed < 3600) {
    return `${Math.floor(elapsed / 60)}m ago`;
  }
  if (elapsed < 86_400) {
    return `${Math.floor(elapsed / 3600)}h ago`;
  }
  return `${Math.floor(elapsed / 86_400)}d ago`;
}

function clampIndex(index: number, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return clampNumber(index, 0, count - 1);
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
