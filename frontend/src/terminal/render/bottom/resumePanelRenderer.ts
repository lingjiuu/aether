import { filterSessions, formatRelativeSessionTime, sessionBasename } from '../../../domain/sessionSummary.js';
import type { UiSessionSummary } from '../../../protocol/wire.js';
import { accent, bold, dim } from '../../shared/ansi.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, line } from '../renderPrimitives.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { visualWidth } from '../../shared/text.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

const RESUME_VISIBLE_COUNT = 8;

export function renderResumePanel(presentation: TerminalPresentation, width: number, maxRows: number): RenderBlock {
  const panel = presentation.commandPanel;
  if (panel?.kind !== 'resume') {
    return { lines: [], cursor: undefined };
  }

  const filteredSessions = filterSessions(panel.sessions, panel.query);
  const selectedIndex = clampIndex(panel.selectedIndex, filteredSessions.length);
  const lines: RenderedLine[] = [
    line(bold('Resume session'), 'Resume session', width),
    blankLine(),
    ...searchBoxLines(panel.query, width),
    blankLine(),
  ];

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
    cursor: { x: Math.min(2 + visualWidth(panel.query), width - 3), y: 3 },
  };
}

function searchBoxLines(query: string, width: number): RenderedLine[] {
  const searchText = query || 'Search...';
  const innerWidth = Math.max(0, width - 4);
  const searchRaw = `│ ${searchText}${' '.repeat(Math.max(0, innerWidth - visualWidth(searchText)))} │`;
  return [
    line(`╭${'─'.repeat(Math.max(0, width - 2))}╮`, `╭${'─'.repeat(Math.max(0, width - 2))}╮`, width),
    line(`│ ${query ? query : dim('Search...')}${' '.repeat(Math.max(0, innerWidth - visualWidth(searchText)))} │`, searchRaw, width),
    line(`╰${'─'.repeat(Math.max(0, width - 2))}╯`, `╰${'─'.repeat(Math.max(0, width - 2))}╯`, width),
  ];
}

function sessionLines(session: UiSessionSummary, selected: boolean, width: number): RenderedLine[] {
  const title = session.name?.trim() || session.preview?.trim() || session.sessionId || 'Untitled session';
  const details = [formatRelativeSessionTime(session.updatedAt ?? session.createdAt), sessionBasename(session.cwd), session.modelId]
    .filter(Boolean)
    .join(' · ');
  const marker = selected ? '❯ ' : '  ';
  return [
    line(`${selected ? accent(marker) : marker}${selected ? accent(title) : title}`, `${marker}${title}`, width),
    details ? line(dim(`    ${details}`), `    ${details}`, width) : blankLine(),
  ];
}
