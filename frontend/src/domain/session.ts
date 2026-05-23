import type { UiSessionState } from '../protocol/wire.js';

export type ConnectionStatus = 'starting' | 'connected' | 'disconnected' | 'error';

export type SessionView = {
  sessionId?: string;
  status?: string;
  name?: string;
  cwd?: string;
  model?: string;
  messageCount: number;
  availableSkillCount: number;
  canContinue: boolean;
  connectionStatus: ConnectionStatus;
};

export function sessionViewFromWire(session?: UiSessionState | null): Partial<SessionView> {
  const modelProvider = session?.summary?.modelProvider;
  const modelId = session?.summary?.modelId;
  return {
    sessionId: session?.sessionId ?? session?.summary?.sessionId ?? undefined,
    status: session?.status ?? undefined,
    name: session?.summary?.name ?? undefined,
    cwd: session?.summary?.cwd ?? undefined,
    model: [modelProvider, modelId].filter(Boolean).join('/') || undefined,
    messageCount: session?.messageCount ?? 0,
    availableSkillCount: session?.availableSkillCount ?? 0,
    canContinue: Boolean(session?.canContinue),
  };
}
