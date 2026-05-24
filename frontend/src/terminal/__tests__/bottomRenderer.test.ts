import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import type { AppState, LocalCommandEntry } from '../../state/reducer.js';
import { stripAnsi } from '../shared/text.js';
import { activeLines, renderView, historyLines } from './renderTestHelpers.js';

describe('bottom renderer', () => {
  it('keeps command-panel cancellation as stable scrollback history', () => {
    const opened = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'resume',
        id: 'resume-1',
        command: '/resume',
        sessions: [],
        selectedIndex: 0,
        query: '',
      },
    });
    const closed = reducer(opened, { type: 'commandPanelClosed', output: 'Resume cancelled' });

    const view = renderView(closed);
    const renderedTranscriptLines = historyLines(view);
    const stableText = renderedTranscriptLines.map(stripAnsi).join('\n');

    expect(view.cursor).toEqual({ x: 2, y: 1 });
    expect(stableText).toContain('❯ /resume');
    expect(stableText).toContain('⎿  Resume cancelled');
  });

  it('uses visual width for committed Chinese input', () => {
    const state = reducer(initialState, { type: 'composerChanged', value: '你好' });
    const view = renderView(state, { composerCursorOffset: '你好'.length });

    expect(view.cursor).toEqual({ x: 6, y: 1 });
    expect(activeLines(view).map(stripAnsi).join('\n')).toContain('❯ 你好');
  });

  it('renders argument placeholders without moving the real cursor past them', () => {
    const state = reducer(initialState, { type: 'composerChanged', value: '/rename ' });
    const view = renderView(state, { composerCursorOffset: '/rename '.length });

    expect(view.cursor).toEqual({ x: 10, y: 1 });
    expect(activeLines(view).map(stripAnsi).join('\n')).toContain('❯ /rename [name]');
  });

  it('renders approval requests as a focused choice panel', () => {
    const state: AppState = {
      ...initialState,
      pendingApproval: {
        request: {
          approvalId: 'approval-1',
          toolName: 'write',
          riskLevel: 'medium',
          arguments: { path: '滕王阁序.md' },
          reason: 'Create a new file',
        },
      },
    };

    const view = renderView(state, { approvalSelectedIndex: 1 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('Approval required: write');
    expect(text).toContain('  Approve');
    expect(text).toContain('› Deny');
    expect(text).not.toContain('/approve');
    expect(text).not.toContain('/deny');
  });

  it('keeps local command backgrounds tight to the command text', () => {
    const localEntry: LocalCommandEntry = {
      id: 'local-1',
      command: '/help',
      output: 'Help dialog dismissed',
      afterTurnOrderLength: 0,
    };
    const view = renderView({ ...initialState, localCommandEntries: [localEntry] }, { columns: 20 });

    const commandLine = historyLines(view).find(line => stripAnsi(line).startsWith('❯ /help')) ?? '';
    expect(commandLine).toContain('\x1b[48;2;48;50;58m');
    expect(stripAnsi(commandLine)).toBe('❯ /help');
  });
});
