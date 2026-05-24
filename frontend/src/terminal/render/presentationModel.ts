import type { PendingApproval } from '../../domain/approval.js';
import type { SessionView } from '../../domain/session.js';
import type { TimelineTurn } from '../../domain/timeline.js';
import type { AppState, CommandPanel, ComposerState, LocalCommandEntry } from '../../state/reducer.js';
import { selectIsRunning, selectTurns } from '../../state/selectors.js';

export type TerminalPresentation = {
  session: SessionView;
  transcriptEpoch: number;
  turns: TimelineTurn[];
  localCommandEntries: LocalCommandEntry[];
  commandPanel?: CommandPanel;
  pendingApproval?: PendingApproval;
  notices: string[];
  composer: ComposerState;
  isRunning: boolean;
};

export function createTerminalPresentation(state: AppState): TerminalPresentation {
  return {
    session: state.session,
    transcriptEpoch: state.transcriptEpoch,
    turns: selectTurns(state),
    localCommandEntries: state.localCommandEntries,
    commandPanel: state.commandPanel,
    pendingApproval: state.pendingApproval,
    notices: state.notices,
    composer: state.composer,
    isRunning: selectIsRunning(state),
  };
}
