import React, { useMemo } from 'react';
import { Box, Text, useInput } from 'ink';
import { getSlashCommandSuggestions } from '../commands/slashCommands.js';
import type { UiSessionSummary } from '../protocol/wire.js';
import type { CommandPanel as CommandPanelState } from '../state/reducer.js';
import { tokens } from '../theme/tokens.js';
import { CommandLine } from './LocalCommandHistory.js';

type Props = {
  panel?: CommandPanelState;
  onClose: (output: string) => void;
  onMoveSelection: (delta: -1 | 1, count: number) => void;
  onQueryChange: (query: string) => void;
  onResume: (sessionId: string) => void;
};

const RESUME_VISIBLE_COUNT = 8;

export function CommandPanel({ panel, onClose, onMoveSelection, onQueryChange, onResume }: Props) {
  const filteredSessions = useMemo(
    () => (panel?.kind === 'resume' ? filterSessions(panel.sessions, panel.query) : []),
    [panel],
  );
  const selectedIndex = panel?.kind === 'resume' ? clampIndex(panel.selectedIndex, filteredSessions.length) : 0;

  useInput(
    (input, key) => {
      if (!panel) {
        return;
      }

      if (key.escape) {
        onClose(panel.kind === 'help' ? 'Help dialog dismissed' : 'Resume cancelled');
        return;
      }

      if (panel.kind !== 'resume') {
        return;
      }

      if (key.upArrow || key.downArrow) {
        onMoveSelection(key.upArrow ? -1 : 1, filteredSessions.length);
        return;
      }

      if (key.return) {
        const selectedSession = filteredSessions[selectedIndex];
        if (selectedSession?.sessionId) {
          onResume(selectedSession.sessionId);
        }
        return;
      }

      if (key.backspace || key.delete) {
        onQueryChange(panel.query.slice(0, -1));
        return;
      }

      if (!input || key.ctrl) {
        return;
      }

      onQueryChange(`${panel.query}${input}`);
    },
    { isActive: Boolean(panel) },
  );

  if (!panel) {
    return null;
  }

  return (
    <Box flexDirection="column" width="100%" flexShrink={0}>
      <CommandLine command={panel.command} />
      {panel.kind === 'help' ? (
        <HelpPanel />
      ) : (
        <ResumePanel panel={panel} sessions={filteredSessions} selectedIndex={selectedIndex} />
      )}
    </Box>
  );
}

function HelpPanel() {
  const commands = getSlashCommandSuggestions('/');
  const usageWidth = Math.max(0, ...commands.map(command => command.usage.length));

  return (
    <Box
      borderStyle="single"
      borderTop
      borderBottom={false}
      borderLeft={false}
      borderRight={false}
      borderColor={tokens.accent}
      flexDirection="column"
      paddingTop={1}
      width="100%"
    >
      <Box flexDirection="row" gap={2}>
        <Text bold>Help</Text>
        <Text color={tokens.dim}>General</Text>
        <Text color={tokens.dim}>Commands</Text>
        <Text color={tokens.dim}>Custom commands</Text>
      </Box>
      <Box height={1} />
      <Text color={tokens.dim}>Browse default commands</Text>
      <Box height={1} />
      {commands.map(command => (
        <Box key={command.usage} flexDirection="row">
          <Box width={usageWidth + 4}>
            <Text>{`  ${command.usage}`}</Text>
          </Box>
          <Text color={tokens.dim} wrap="truncate-end">
            {command.description}
          </Text>
        </Box>
      ))}
      <Box height={1} />
      <Text color={tokens.dim}>Esc to cancel</Text>
    </Box>
  );
}

function ResumePanel({
  panel,
  sessions,
  selectedIndex,
}: {
  panel: Extract<CommandPanelState, { kind: 'resume' }>;
  sessions: UiSessionSummary[];
  selectedIndex: number;
}) {
  const visibleStart = Math.max(
    0,
    Math.min(Math.max(selectedIndex - 4, 0), Math.max(sessions.length - RESUME_VISIBLE_COUNT, 0)),
  );
  const visibleSessions = sessions.slice(visibleStart, visibleStart + RESUME_VISIBLE_COUNT);

  return (
    <Box
      borderStyle="single"
      borderTop
      borderBottom={false}
      borderLeft={false}
      borderRight={false}
      borderColor={tokens.accent}
      flexDirection="column"
      paddingTop={1}
      width="100%"
    >
      <Text bold>Resume session</Text>
      <Box borderStyle="round" borderColor={tokens.border} paddingX={1} marginTop={1} width="100%">
        <Text color={panel.query ? undefined : tokens.dim}>{panel.query || 'Search...'}</Text>
      </Box>
      {sessions.length ? (
        <Box flexDirection="column" marginTop={1}>
          {visibleSessions.map((session, offset) => {
            const index = visibleStart + offset;
            const selected = index === selectedIndex;
            return <SessionRow key={session.sessionId ?? `${index}`} session={session} selected={selected} />;
          })}
        </Box>
      ) : (
        <Box marginTop={1}>
          <Text color={tokens.dim}>{panel.query ? 'No matching sessions' : 'No sessions found'}</Text>
        </Box>
      )}
      <Box height={1} />
      <Text color={tokens.dim}>Up/Down to select · Enter to resume · Esc to cancel</Text>
    </Box>
  );
}

function SessionRow({ session, selected }: { session: UiSessionSummary; selected: boolean }) {
  const title = session.name?.trim() || session.preview?.trim() || session.sessionId || 'Untitled session';
  const details = [formatRelativeTime(session.updatedAt ?? session.createdAt), basename(session.cwd), session.modelId]
    .filter(Boolean)
    .join(' · ');

  return (
    <Box flexDirection="column">
      <Text color={selected ? tokens.accent : undefined} wrap="truncate-end">
        {selected ? '❯ ' : '  '}
        {title}
      </Text>
      {details ? <Text color={tokens.dim}>{`    ${details}`}</Text> : null}
    </Box>
  );
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

function formatRelativeTime(timestamp?: number | null): string {
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
  return Math.max(0, Math.min(index, count - 1));
}
