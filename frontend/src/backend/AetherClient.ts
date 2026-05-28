import type {
  InitializeResult,
  SkillInfo,
  TurnInputItem,
  UiCommandAck,
  UiEvent,
  UiEventPage,
  UiHistory,
  UiModelCatalog,
  UiPermissionCatalog,
  UiSessionState,
  UiSessionSummary,
} from '../protocol/wire.js';
import { parseUiEvent } from '../protocol/schema.js';
import { StdioTransport, type StdioTransportOptions } from './StdioTransport.js';

export class AetherClient {
  private readonly transport: StdioTransport;

  constructor(options: StdioTransportOptions) {
    this.transport = new StdioTransport(options);
  }

  start(): void {
    this.transport.start();
  }

  onEvent(handler: (event: UiEvent) => void): () => void {
    return this.transport.onNotification(notification => {
      if (notification.method === 'event') {
        const event = parseUiEvent(notification.params);
        if (event) {
          handler(event);
        }
      }
    });
  }

  onStderr(handler: (text: string) => void): () => void {
    return this.transport.onStderr(handler);
  }

  initialize(): Promise<InitializeResult> {
    return this.transport.request('initialize');
  }

  initialized(): Promise<{ ok: boolean }> {
    return this.transport.request('initialized');
  }

  currentSession(): Promise<UiSessionState> {
    return this.transport.request('session/current');
  }

  listSessions(): Promise<UiSessionSummary[]> {
    return this.transport.request('session/list');
  }

  history(): Promise<UiHistory> {
    return this.transport.request('history/read');
  }

  eventsAfter(afterSequence: number, limit = 200): Promise<UiEventPage> {
    return this.transport.request('events/list', { afterSequence, limit });
  }

  submit(items: TurnInputItem[]): Promise<UiCommandAck> {
    return this.transport.request('turn/submit', { items });
  }

  cancelTurn(): Promise<UiCommandAck> {
    return this.transport.request('turn/cancel');
  }

  compact(): Promise<UiCommandAck> {
    return this.transport.request('compact/run');
  }

  listModels(): Promise<UiModelCatalog> {
    return this.transport.request('model/list');
  }

  setModel(providerId: string | undefined, modelId: string, reasoningEffort?: string): Promise<UiCommandAck> {
    return this.transport.request('model/set', { providerId, modelId, reasoningEffort });
  }

  listPermissions(): Promise<UiPermissionCatalog> {
    return this.transport.request('permission/list');
  }

  setPermissionMode(permissionMode: string): Promise<UiCommandAck> {
    return this.transport.request('permission/set', { permissionMode });
  }

  newSession(cwd: string): Promise<UiCommandAck> {
    const sessionCwd = cwd.trim();
    if (!sessionCwd) {
      throw new Error('Aether session cwd is required.');
    }
    return this.transport.request('session/new', { cwd: sessionCwd });
  }

  resume(sessionId: string): Promise<UiCommandAck> {
    return this.transport.request('session/resume', { sessionId });
  }

  setSessionName(name: string): Promise<UiCommandAck> {
    return this.transport.request('session/name/set', { name });
  }

  listSkills(forceReload = false): Promise<SkillInfo[]> {
    return this.transport.request('skills/list', { forceReload });
  }

  respondToApproval(approvalId: string, approved: boolean, reason?: string): Promise<UiCommandAck> {
    return this.transport.request('approval/respond', { approvalId, approved, reason });
  }

  close(): void {
    this.transport.close();
  }
}
