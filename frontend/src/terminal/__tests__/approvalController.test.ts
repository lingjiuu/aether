import { describe, expect, it, vi } from 'vitest';
import type { AetherClient } from '../../backend/AetherClient.js';
import { initialState, type AppAction, type AppState } from '../../state/reducer.js';
import { ApprovalController } from '../interaction/approvalController.js';

describe('ApprovalController', () => {
  it('resets selection when the active approval changes', () => {
    const controller = new ApprovalController();
    controller.sync(stateWithApproval('first'));

    void controller.handleKey({ kind: 'down' }, stateWithApproval('first'), clientStub(), {
      dispatch: vi.fn(),
      render: vi.fn(),
    });
    expect(controller.selection).toBe(1);

    controller.sync(stateWithApproval('second'));
    expect(controller.selection).toBe(0);
  });

  it('responds with deny when escape is pressed', async () => {
    const respondToApproval = vi.fn().mockResolvedValue({ ok: true });
    const client = clientStub({ respondToApproval });
    const actions: AppAction[] = [];

    await new ApprovalController().handleKey({ kind: 'escape' }, stateWithApproval('approval-1'), client, {
      dispatch: action => actions.push(action),
      render: vi.fn(),
    });

    expect(respondToApproval).toHaveBeenCalledWith('approval-1', false);
    expect(actions).toEqual([]);
  });
});

function stateWithApproval(approvalId: string): AppState {
  return {
    ...initialState,
    pendingApproval: {
      request: {
        approvalId,
        toolName: 'write',
      },
    },
  };
}

function clientStub(overrides: Partial<AetherClient> = {}): AetherClient {
  return {
    respondToApproval: vi.fn().mockResolvedValue({ ok: true }),
    ...overrides,
  } as unknown as AetherClient;
}
