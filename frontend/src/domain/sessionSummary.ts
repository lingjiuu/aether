import type { UiSessionSummary } from '../protocol/wire.js';

export type SessionSearchItem = {
  name?: string | null;
  preview?: string | null;
  sessionId?: string | null;
  cwd?: string | null;
  modelId?: string | null;
  createdAt?: number | null;
  updatedAt?: number | null;
};

export function filterSessions<T extends SessionSearchItem>(sessions: T[], query: string): T[] {
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

export function formatSessionList(sessions: UiSessionSummary[]): string {
  if (!sessions.length) {
    return 'No sessions found.';
  }
  return sessions
    .slice(0, 8)
    .map(session => {
      const title = session.name?.trim() || session.preview?.trim() || session.sessionId || 'Untitled session';
      const details = [formatRelativeSessionTime(session.updatedAt ?? session.createdAt), sessionBasename(session.cwd), session.modelId]
        .filter(Boolean)
        .join(' · ');
      return details ? `${title}\n    ${details}` : title;
    })
    .join('\n');
}

export function sessionBasename(path?: string | null): string {
  const normalized = path?.replace(/\/+$/, '');
  return normalized?.split('/').filter(Boolean).at(-1) ?? '';
}

export function formatRelativeSessionTime(timestamp?: number | null): string {
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
