import type { AetherClient } from '../../backend/AetherClient.js';
import type { AppAction, AppState } from '../../state/reducer.js';
import type { Key } from '../input/inputParser.js';
import { moveIndex } from '../shared/terminalMath.js';

type ApprovalCallbacks = {
  dispatch: (action: AppAction) => void;
  render: () => void;
};

export class ApprovalController {
  private selectedIndex = 0;
  private approvalId?: string;

  get selection(): number {
    return this.selectedIndex;
  }

  sync(state: AppState): void {
    const approvalId = state.pendingApproval?.request.approvalId ?? undefined;
    if (approvalId !== this.approvalId) {
      this.approvalId = approvalId;
      this.selectedIndex = 0;
    }
  }

  async handleKey(key: Key, state: AppState, client: AetherClient, callbacks: ApprovalCallbacks): Promise<boolean> {
    if (!state.pendingApproval) {
      return false;
    }

    switch (key.kind) {
      case 'up':
        this.selectedIndex = moveIndex(this.selectedIndex, -1, 2);
        callbacks.render();
        return true;
      case 'down':
        this.selectedIndex = moveIndex(this.selectedIndex, 1, 2);
        callbacks.render();
        return true;
      case 'return':
        await this.respond(state, client, callbacks.dispatch, this.selectedIndex === 0);
        return true;
      case 'escape':
        await this.respond(state, client, callbacks.dispatch, false);
        return true;
      default:
        return true;
    }
  }

  private async respond(
    state: AppState,
    client: AetherClient,
    dispatch: (action: AppAction) => void,
    approved: boolean,
  ): Promise<void> {
    const approvalId = state.pendingApproval?.request.approvalId;
    if (!approvalId) {
      return;
    }
    try {
      await client.respondToApproval(approvalId, approved);
    } catch (error) {
      dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
    }
  }
}
