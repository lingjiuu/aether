import { sessionViewFromWire, type SessionView } from '../domain/session.js';
import type { PendingApproval } from '../domain/approval.js';
import type { TimelineItem, TimelineStatus, TimelineTurn } from '../domain/timeline.js';
import { eventTurnKey, payload } from '../protocol/events.js';
import type {
  UiEvent,
  UiHistory,
  UiHistoryItem,
  UiItem,
  UiItemKind,
  UiSessionState,
  UiSessionSummary,
  UiTokenUsage,
  UiToolCall,
  UiToolResult,
  UiToolUpdate,
} from '../protocol/wire.js';

export type AppState = {
  session: SessionView;
  turns: Record<string, TimelineTurn>;
  turnOrder: string[];
  localCommandEntries: LocalCommandEntry[];
  commandPanel?: CommandPanel;
  pendingApproval?: PendingApproval;
  sessions: UiSessionSummary[];
  tokenUsage?: UiTokenUsage;
  notices: string[];
  composer: {
    value: string;
    commandPaletteOpen: boolean;
    selectedSuggestionIndex: number;
  };
  lastSequence: number;
};

export type LocalCommandEntry = {
  id: string;
  command: string;
  output: string;
  afterTurnOrderLength: number;
};

export type CommandPanel =
  | { kind: 'help'; id: string; command: '/help' }
  | {
      kind: 'resume';
      id: string;
      command: '/resume';
      sessions: UiSessionSummary[];
      selectedIndex: number;
      query: string;
    };

export type AppAction =
  | { type: 'connected'; session?: UiSessionState | null }
  | { type: 'disconnected' }
  | { type: 'sessionState'; session: UiSessionState }
  | { type: 'history'; history: UiHistory }
  | { type: 'event'; event: UiEvent }
  | { type: 'sessions'; sessions: UiSessionSummary[] }
  | { type: 'composerChanged'; value: string }
  | { type: 'composerSuggestionMoved'; delta: -1 | 1; count: number }
  | { type: 'composerSuggestionSelected'; index: number }
  | { type: 'commandPanelOpened'; panel: CommandPanel }
  | { type: 'commandPanelSelectionMoved'; delta: -1 | 1; count: number }
  | { type: 'commandPanelQueryChanged'; query: string }
  | { type: 'commandPanelClosed'; output: string }
  | { type: 'localCommandCompleted'; id: string; command: string; output: string }
  | { type: 'notice'; message: string };

export const initialState: AppState = {
  session: {
    messageCount: 0,
    availableSkillCount: 0,
    canContinue: false,
    connectionStatus: 'starting',
  },
  turns: {},
  turnOrder: [],
  localCommandEntries: [],
  sessions: [],
  notices: [],
  composer: {
    value: '',
    commandPaletteOpen: false,
    selectedSuggestionIndex: 0,
  },
  lastSequence: 0,
};

export function reducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'connected':
      return {
        ...state,
        session: {
          ...state.session,
          ...sessionViewFromWire(action.session),
          connectionStatus: 'connected',
        },
      };
    case 'disconnected':
      return {
        ...state,
        session: { ...state.session, connectionStatus: 'disconnected' },
      };
    case 'sessionState':
      return {
        ...state,
        session: {
          ...state.session,
          ...sessionViewFromWire(action.session),
        },
        tokenUsage: action.session.tokenUsage ?? state.tokenUsage,
      };
    case 'history':
      return applyHistory(state, action.history);
    case 'event':
      return applyEvent(state, action.event);
    case 'sessions':
      return { ...state, sessions: action.sessions };
    case 'composerChanged':
      return {
        ...state,
        composer: {
          value: action.value,
          commandPaletteOpen: action.value.trimStart().startsWith('/'),
          selectedSuggestionIndex: 0,
        },
      };
    case 'composerSuggestionMoved':
      return {
        ...state,
        composer: {
          ...state.composer,
          selectedSuggestionIndex: moveSuggestionIndex(
            state.composer.selectedSuggestionIndex,
            action.delta,
            action.count,
          ),
        },
      };
    case 'composerSuggestionSelected':
      return {
        ...state,
        composer: {
          ...state.composer,
          selectedSuggestionIndex: Math.max(0, action.index),
        },
      };
    case 'commandPanelOpened':
      return {
        ...state,
        commandPanel: action.panel,
        composer: {
          ...state.composer,
          value: '',
          commandPaletteOpen: false,
          selectedSuggestionIndex: 0,
        },
      };
    case 'commandPanelSelectionMoved':
      return moveCommandPanelSelection(state, action.delta, action.count);
    case 'commandPanelQueryChanged':
      return state.commandPanel?.kind === 'resume'
        ? { ...state, commandPanel: { ...state.commandPanel, query: action.query, selectedIndex: 0 } }
        : state;
    case 'commandPanelClosed':
      return closeCommandPanel(state, action.output);
    case 'localCommandCompleted':
      return appendLocalCommand(
        state,
        {
          id: action.id,
          command: action.command,
          output: action.output,
        },
        state.commandPanel,
      );
    case 'notice':
      return { ...state, notices: [...state.notices.slice(-4), action.message] };
  }
}

function moveCommandPanelSelection(state: AppState, delta: -1 | 1, count: number): AppState {
  if (state.commandPanel?.kind !== 'resume') {
    return state;
  }
  return {
    ...state,
    commandPanel: {
      ...state.commandPanel,
      selectedIndex: moveSuggestionIndex(state.commandPanel.selectedIndex, delta, count),
    },
  };
}

function closeCommandPanel(state: AppState, output: string): AppState {
  const panel = state.commandPanel;
  if (!panel) {
    return state;
  }
  return appendLocalCommand(
    state,
    {
      id: panel.id,
      command: panel.command,
      output,
    },
    undefined,
  );
}

function appendLocalCommand(
  state: AppState,
  entry: Pick<LocalCommandEntry, 'id' | 'command' | 'output'>,
  commandPanel: CommandPanel | undefined,
): AppState {
  return {
    ...state,
    commandPanel,
    localCommandEntries: [
      ...state.localCommandEntries,
      {
        ...entry,
        afterTurnOrderLength: state.turnOrder.length,
      },
    ],
  };
}

function moveSuggestionIndex(current: number, delta: -1 | 1, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return (current + delta + count) % count;
}

function applyHistory(state: AppState, history: UiHistory): AppState {
  const turns: Record<string, TimelineTurn> = {};
  const turnOrder: string[] = [];
  for (const sourceTurn of history.turns ?? []) {
    const turnId = sourceTurn.turnId ?? `turn-${sourceTurn.turn ?? turnOrder.length + 1}`;
    turns[turnId] = {
      turnId,
      commandId: sourceTurn.commandId,
      turn: sourceTurn.turn,
      status: sourceTurn.status ?? 'COMPLETED',
      startedAtMs: undefined,
      endedAtMs: undefined,
      items: (sourceTurn.items ?? []).map(historyItemToTimelineItem),
    };
    turnOrder.push(turnId);
  }
  return {
    ...state,
    session: {
      ...state.session,
      sessionId: history.sessionId ?? state.session.sessionId,
    },
    turns,
    turnOrder,
    localCommandEntries: [],
    commandPanel: undefined,
  };
}

function applyEvent(state: AppState, event: UiEvent): AppState {
  const lastSequence = Math.max(state.lastSequence, event.sequence ?? 0);

  switch (event.type) {
    case 'SESSION_RESET':
      return { ...state, turns: {}, turnOrder: [], lastSequence };
    case 'SESSION_NAME_UPDATED': {
      const data = payload(event, 'sessionName');
      return {
        ...state,
        session: { ...state.session, name: data?.name ?? state.session.name },
        lastSequence,
      };
    }
    case 'TOKEN_USAGE': {
      const data = payload(event, 'tokenUsage');
      return { ...state, tokenUsage: data?.tokenUsage ?? state.tokenUsage, lastSequence };
    }
    case 'APPROVAL_REQUESTED': {
      const data = payload(event, 'approval');
      return {
        ...state,
        pendingApproval: data?.request ? { request: data.request } : state.pendingApproval,
        lastSequence,
      };
    }
    case 'APPROVAL_RESOLVED':
      return { ...state, pendingApproval: undefined, lastSequence };
    case 'SKILLS_CHANGED': {
      const text = payload(event, 'text')?.text ?? 'skills changed';
      return { ...state, notices: [...state.notices.slice(-4), text], lastSequence };
    }
    default:
      return applyTimelineEvent({ ...state, lastSequence }, event);
  }
}

function applyTimelineEvent(state: AppState, event: UiEvent): AppState {
  switch (event.type) {
    case 'TURN_STARTED':
      return updateTurn(state, event, turn => ({
        ...turn,
        status: 'RUNNING',
        startedAtMs: turn.startedAtMs ?? event.timestampMs,
        endedAtMs: undefined,
      }));
    case 'TURN_COMPLETED':
      return updateTurn(state, event, turn => ({
        ...turn,
        status: 'COMPLETED',
        endedAtMs: event.timestampMs ?? turn.endedAtMs,
      }));
    case 'TURN_ABORTED':
      return updateTurn(state, event, turn => ({
        ...turn,
        status: 'ABORTED',
        endedAtMs: event.timestampMs ?? turn.endedAtMs,
      }));
    case 'USER_MESSAGE': {
      const item = payload(event, 'userMessage')?.item;
      return item ? upsertItem(state, event, itemToTimelineItem(item, 'COMPLETED')) : state;
    }
    case 'CONTEXT_MESSAGE': {
      const item = payload(event, 'contextMessage')?.item;
      return item ? upsertItem(state, event, itemToTimelineItem(item, 'COMPLETED')) : state;
    }
    case 'ITEM_STARTED': {
      const data = payload(event, 'itemStarted');
      if (!data?.itemId || !data.itemKind) {
        return state;
      }
      return upsertPartialItem(state, event, data.itemId, {
        id: data.itemId,
        kind: data.itemKind,
        status: 'RUNNING',
        contentIndex: data.contentIndex,
        text: '',
        toolCall: data.toolCall ?? undefined,
      });
    }
    case 'ASSISTANT_TEXT_DELTA':
    case 'REASONING_DELTA': {
      const data = payload(event, 'textDelta');
      if (!data?.itemId || !data.itemKind || !data.delta) {
        return state;
      }
      return upsertPartialItem(state, event, data.itemId, current => ({
        id: data.itemId ?? current.id,
        kind: data.itemKind ?? current.kind,
        status: 'RUNNING',
        contentIndex: data.contentIndex,
        text: `${current.text ?? ''}${data.delta ?? ''}`,
      }));
    }
    case 'ITEM_COMPLETED': {
      const item = payload(event, 'itemCompleted')?.item;
      return item ? upsertItem(state, event, itemToTimelineItem(item, 'COMPLETED')) : state;
    }
    case 'TOOL_CALL_ARGUMENTS_DELTA': {
      const data = payload(event, 'toolArgumentsDelta');
      if (!data?.itemId) {
        return state;
      }
      return upsertPartialItem(state, event, data.itemId, current => ({
        ...current,
        id: data.itemId ?? current.id,
        kind: 'TOOL_CALL',
        status: 'RUNNING',
        contentIndex: data.contentIndex,
        toolCall: data.toolCall ?? current.toolCall,
      }));
    }
    case 'TOOL_CALL_ARGUMENTS_DONE': {
      const item = payload(event, 'toolArgumentsDone')?.item;
      return item ? upsertItem(state, event, itemToTimelineItem(item, 'COMPLETED')) : state;
    }
    case 'TOOL_CALL': {
      const toolCall = payload(event, 'toolCall')?.toolCall;
      return applyToolCall(state, event, toolCall);
    }
    case 'TOOL_EXECUTION_BEGIN':
    case 'TOOL_EXECUTION_UPDATE':
    case 'TOOL_EXECUTION_END': {
      const data = payload(event, 'toolExecution');
      return applyToolExecution(state, event, data?.toolCall, data?.toolUpdate, data?.toolResult);
    }
    case 'TOOL_RESULT': {
      const item = payload(event, 'toolResult')?.item;
      return item ? applyToolResultItem(state, event, item) : state;
    }
    case 'COMPACT_STARTED':
      return addSystemItem(state, event, 'RUNNING', payload(event, 'compact')?.text ?? 'Compacting conversation...');
    case 'COMPACT_FINISHED':
      return addSystemItem(state, event, 'COMPLETED', payload(event, 'compact')?.text ?? 'Conversation compacted.');
    case 'COMPACT_SKIPPED':
      return addSystemItem(state, event, 'SKIPPED', payload(event, 'compact')?.text ?? 'Compact skipped.');
    case 'ERROR':
      return addSystemItem(state, event, 'ERROR', payload(event, 'error')?.message ?? 'Unknown error');
    default:
      return state;
  }
}

function applyToolCall(state: AppState, event: UiEvent, toolCall?: UiToolCall | null): AppState {
  const itemId = toolCall?.itemId ?? toolCall?.toolCallId;
  if (!itemId) {
    return state;
  }
  return upsertPartialItem(state, event, itemId, current => ({
    ...current,
    id: itemId,
    kind: 'TOOL_CALL',
    status: current.status || 'COMPLETED',
    contentIndex: toolCall?.contentIndex,
    toolCall: toolCall ?? current.toolCall,
  }));
}

function applyToolExecution(
  state: AppState,
  event: UiEvent,
  toolCall?: UiToolCall | null,
  toolUpdate?: UiToolUpdate | null,
  toolResult?: UiToolResult | null,
): AppState {
  const itemId = toolCall?.itemId ?? toolCall?.toolCallId;
  if (!itemId) {
    return state;
  }
  return upsertPartialItem(state, event, itemId, current => ({
    ...current,
    id: itemId,
    kind: 'TOOL_CALL',
    status: toolUpdate?.status ?? 'RUNNING',
    contentIndex: toolCall?.contentIndex,
    toolCall: toolCall ?? current.toolCall,
    toolUpdate: toolUpdate ?? current.toolUpdate,
    toolResult: toolResult ?? current.toolResult,
  }));
}

function applyToolResultItem(state: AppState, event: UiEvent, item: UiItem): AppState {
  const resultItem = itemToTimelineItem(item, 'COMPLETED');
  const sourceItemId = resultItem.toolResult?.sourceItemId;
  if (!sourceItemId) {
    return upsertItem(state, event, resultItem);
  }
  return upsertPartialItem(state, event, sourceItemId, current => ({
    ...current,
    status: resultItem.toolResult?.status ?? 'COMPLETED',
    toolUpdate: undefined,
    toolResult: resultItem.toolResult ?? current.toolResult,
  }));
}

function addSystemItem(state: AppState, event: UiEvent, status: TimelineStatus, text: string): AppState {
  const id = `${event.type?.toLowerCase() ?? 'system'}-${event.sequence ?? Date.now()}`;
  return upsertItem(state, event, {
    id,
    kind: 'CONTEXT_MESSAGE',
    status,
    text,
  });
}

function updateTurn(
  state: AppState,
  event: UiEvent,
  update: (turn: TimelineTurn) => TimelineTurn,
): AppState {
  const [next, turn] = ensureTurn(state, event);
  next.turns[turn.turnId] = update(turn);
  return next;
}

function upsertItem(state: AppState, event: UiEvent, item: TimelineItem): AppState {
  return upsertPartialItem(state, event, item.id, item);
}

function upsertPartialItem(
  state: AppState,
  event: UiEvent,
  itemId: string,
  patch: TimelineItem | ((current: TimelineItem) => TimelineItem),
): AppState {
  const [next, turn] = ensureTurn(state, event);
  const current = turn.items.find(item => item.id === itemId) ?? {
    id: itemId,
    kind: 'CONTEXT_MESSAGE' as UiItemKind,
    status: 'RUNNING',
    text: '',
  };
  const updated = typeof patch === 'function' ? patch(current) : { ...current, ...patch };
  const items = turn.items.some(item => item.id === itemId)
    ? turn.items.map(item => (item.id === itemId ? updated : item))
    : [...turn.items, updated];
  next.turns[turn.turnId] = { ...turn, items };
  return next;
}

function ensureTurn(state: AppState, event: UiEvent): [AppState, TimelineTurn] {
  const turnId = eventTurnKey(event);
  const existing = state.turns[turnId];
  if (existing) {
    return [
      {
        ...state,
        turns: { ...state.turns },
        turnOrder: [...state.turnOrder],
      },
      existing,
    ];
  }

  const created: TimelineTurn = {
    turnId,
    commandId: event.commandId,
    turn: event.turn,
    status: 'RUNNING',
    startedAtMs: event.timestampMs,
    endedAtMs: undefined,
    items: [],
  };
  return [
    {
      ...state,
      turns: { ...state.turns, [turnId]: created },
      turnOrder: [...state.turnOrder, turnId],
    },
    created,
  ];
}

function historyItemToTimelineItem(item: UiHistoryItem): TimelineItem {
  return {
    id: item.id ?? fallbackItemId(item.kind, item.contentIndex),
    kind: item.kind ?? 'CONTEXT_MESSAGE',
    status: item.status ?? 'COMPLETED',
    contentIndex: item.contentIndex,
    text: item.text ?? '',
    toolCall: item.toolCall ?? undefined,
    toolUpdate: item.toolUpdate ?? undefined,
    toolResult: item.toolResult ?? undefined,
  };
}

function itemToTimelineItem(item: UiItem, status: TimelineStatus): TimelineItem {
  const body = item.body;
  return {
    id: item.itemId ?? fallbackItemId(item.kind, item.contentIndex),
    kind: item.kind ?? 'CONTEXT_MESSAGE',
    status,
    contentIndex: item.contentIndex,
    text: body?.bodyType === 'text' ? body.text ?? '' : '',
    toolCall: body?.bodyType === 'toolCall' ? body.toolCall ?? undefined : undefined,
    toolResult: body?.bodyType === 'toolResult' ? body.toolResult ?? undefined : undefined,
  };
}

function fallbackItemId(kind?: UiItemKind | null, contentIndex?: number | null): string {
  return `${kind ?? 'CONTEXT_MESSAGE'}-${contentIndex ?? 0}`;
}
