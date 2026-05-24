import type { PendingApproval } from '../domain/approval.js';
import type { SessionView } from '../domain/session.js';
import type { TimelineTurn } from '../domain/timeline.js';
import type {
  UiEvent,
  UiHistory,
  UiSessionState,
  UiSessionSummary,
  UiTokenUsage,
} from '../protocol/wire.js';

export type AppState = {
  session: SessionView;
  transcriptEpoch: number;
  turns: Record<string, TimelineTurn>;
  turnOrder: string[];
  localCommandEntries: LocalCommandEntry[];
  commandPanel?: CommandPanel;
  pendingApproval?: PendingApproval;
  tokenUsage?: UiTokenUsage;
  notices: string[];
  composer: ComposerState;
  lastSequence: number;
};

export type ComposerState = {
  value: string;
  commandPaletteOpen: boolean;
  selectedSuggestionIndex: number;
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
  transcriptEpoch: 0,
  turns: {},
  turnOrder: [],
  localCommandEntries: [],
  notices: [],
  composer: {
    value: '',
    commandPaletteOpen: false,
    selectedSuggestionIndex: 0,
  },
  lastSequence: 0,
};
