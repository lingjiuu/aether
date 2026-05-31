import { describe, expect, it } from 'vitest';
import { initialState, reducer } from '../../state/reducer.js';
import type { AppState, LocalCommandEntry } from '../../state/reducer.js';
import { stripAnsi, visualWidth } from '../shared/text.js';
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

  it('renders slash command suggestions under the composer', () => {
    const state = reducer(initialState, { type: 'composerChanged', value: '/' });
    const view = renderView(state, { columns: 100, rows: 30, composerCursorOffset: 1 });
    const lines = activeLines(view).map(stripAnsi);
    const promptIndex = lines.findIndex(renderedLine => renderedLine === '❯ /');
    const suggestionIndex = lines.findIndex(renderedLine => renderedLine.includes('/new'));

    expect(promptIndex).toBeGreaterThan(-1);
    expect(suggestionIndex).toBeGreaterThan(promptIndex);
  });

  it('aligns composer popup metadata after wide file names', () => {
    const state = reducer(
      reducer(initialState, { type: 'composerChanged', value: '@' }),
      {
        type: 'composerPopupChanged',
        popup: {
          kind: 'file',
          query: '',
          items: [
            { path: '/repo/hello.txt', displayPath: 'hello.txt', isImage: false },
            { path: '/repo/滕王阁序.md', displayPath: '滕王阁序.md', isImage: false },
          ],
        },
      },
    );
    const view = renderView(state, { columns: 100, rows: 30, composerCursorOffset: 1 });
    const popupLines = activeLines(view).map(stripAnsi).filter(renderedLine => renderedLine.includes(' file'));
    const fileColumns = popupLines.map(fileColumn);

    expect(popupLines).toHaveLength(2);
    expect(fileColumns[0]).toBe(fileColumns[1]);
  });

  it('hides the footer while slash command suggestions are open', () => {
    const home = process.env.HOME ?? '/Users/Apple';
    const state = reducer(
      {
        ...initialState,
        session: {
          ...initialState.session,
          model: 'gpt-5.5',
          reasoningEffort: 'HIGH',
          cwd: `${home}/code/MyProjects/test0`,
        },
      },
      { type: 'composerChanged', value: '/' },
    );
    const view = renderView(state, { columns: 100, rows: 30, composerCursorOffset: 1 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('/model');
    expect(text).not.toContain('gpt-5.5 high · ~/code/MyProjects/test0');
  });

  it('renders model and cwd in the footer instead of shortcuts', () => {
    const home = process.env.HOME ?? '/Users/Apple';
    const state: AppState = {
      ...initialState,
      session: {
        ...initialState.session,
        model: 'ikun-openai/gpt-5.5',
        reasoningEffort: 'HIGH',
        cwd: `${home}/code/MyProjects/test0`,
      },
    };

    const view = renderView(state, { columns: 100, rows: 30 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('gpt-5.5 high · ~/code/MyProjects/test0');
    expect(text).not.toContain('ikun-openai/gpt-5.5');
    expect(text).not.toContain('? for shortcuts');
  });

  it('renders transient footer status on the right without replacing model context', () => {
    const home = process.env.HOME ?? '/Users/Apple';
    const state: AppState = {
      ...initialState,
      session: {
        ...initialState.session,
        model: 'ikun-openai/gpt-5.4-mini',
        reasoningEffort: 'XHIGH',
        cwd: `${home}/code/MyProjects/test0`,
      },
      turns: {
        running: {
          turnId: 'running',
          status: 'RUNNING',
          items: [],
        },
      },
      turnOrder: ['running'],
    };

    const view = renderView(state, { columns: 100, rows: 30 });
    const footer = activeLines(view).map(stripAnsi).at(-1) ?? '';

    expect(footer).toContain('gpt-5.4-mini xhigh · ~/code/MyProjects/test0');
    expect(footer).toContain('esc to interrupt');
    expect(footer.indexOf('esc to interrupt')).toBeGreaterThan(footer.indexOf('~/code/MyProjects/test0'));
  });

  it('renders approval requests as a focused choice panel', () => {
    const state: AppState = {
      ...initialState,
      pendingApproval: {
        request: {
          approvalId: 'approval-1',
          toolName: 'Bash',
          riskLevel: 'medium',
          arguments: { command: 'rm /Users/Apple/code/MyProjects/test0/hello.txt' },
          reason: 'Tool can execute commands.',
        },
      },
    };

    const view = renderView(state, { approvalSelectedIndex: 1 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('Bash Command');
    expect(text).toContain('$ rm /Users/Apple/code/MyProjects/test0/hello.txt');
    expect(text).toContain('  1. Yes, proceed (y)');
    expect(text).toContain('❯ 2. No, deny (esc)');
    expect(text).toContain('Enter confirm · ↑/↓ select · y yes · n no');
    expect(text).not.toContain('Approval required');
    expect(text).not.toContain('Reason');
    expect(text).not.toContain('Tool can execute commands.');
    expect(text).not.toContain('gpt-');
    expect(text).not.toContain('/approve');
    expect(text).not.toContain('/deny');
  });

  it('renders the model panel in the Claude-style command area', () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'model',
        id: 'model-1',
        command: '/model',
        catalog: {
          current: { providerId: 'fake', modelId: 'second', reasoningEffort: 'XHIGH' },
          models: [
            { providerId: 'fake', modelId: 'first', name: 'Custom Opus model' },
            { providerId: 'fake', modelId: 'second', name: 'Custom Sonnet model', current: true },
          ],
          reasoningEfforts: ['NONE', 'XHIGH', 'HIGH', 'LOW'],
        },
        selectedIndex: 1,
        reasoningIndex: 2,
        customModel: '',
      },
    });

    const view = renderView(state, { columns: 100, rows: 30 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('❯ /model');
    expect(text).toContain('Select model');
    expect(text).toContain('Provider: fake');
    expect(text).toContain('❯ 2. second ✔');
    expect(text).not.toContain('fake/second');
    expect(text).toContain('Custom model');
    expect(text).toContain('model id');
    expect(text).toContain('xHigh effort ←/→ to adjust');
    expect(text).not.toContain('None effort');
    expect(text).toContain('↑/↓ select · Type custom · Enter confirm · Esc cancel');
  });

  it('renders the permissions panel in the Claude-style command area', () => {
    const state = reducer(initialState, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'permissions',
        id: 'permissions-1',
        command: '/permissions',
        catalog: {
          current: { id: 'DEFAULT', name: 'Default', current: true },
          modes: [
            {
              id: 'DEFAULT',
              name: 'Default',
              description: 'Workspace writes are allowed; shell commands and outside-workspace writes ask first.',
              current: true,
            },
            {
              id: 'FULL_ACCESS',
              name: 'Full Access',
              description: 'Allow tools without approval, including shell commands and outside-workspace edits.',
            },
          ],
        },
        selectedIndex: 1,
      },
    });

    const view = renderView(state, { columns: 110, rows: 30 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('❯ /permissions');
    expect(text).toContain('Permissions');
    expect(text).toContain('1. Default ✓');
    expect(text).toContain('❯ 2. Full Access');
    expect(text).toContain('Enter to confirm · Esc to cancel');
  });

  it('renders the skills panel in the Claude-style command area', () => {
    const state = reducer({ ...initialState, session: { ...initialState.session, cwd: '/Users/Apple/code/MyProjects/test0' } }, {
      type: 'commandPanelOpened',
      panel: {
        kind: 'skills',
        id: 'skills-1',
        command: '/skills',
        skills: [
          {
            name: 'codex-token-usage',
            description: 'Show token usage',
            location: '/Users/Apple/code/MyProjects/test0/.aether/skills/codex-token-usage/SKILL.md',
          },
        ],
        selectedIndex: 0,
        query: '',
      },
    });

    const view = renderView(state, { columns: 100, rows: 30 });
    const text = activeLines(view).map(stripAnsi).join('\n');

    expect(text).toContain('❯ /skills');
    expect(text).toContain('Skills');
    expect(text).toContain('1 skill · / to search · Enter to close · Esc to cancel');
    expect(text).toContain('⌕ Search skills…');
    expect(text).toContain('❯ ✓ on  codex-token-usage · project');
    expect(text).not.toContain('? for shortcuts');
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

function fileColumn(line: string): number {
  const index = line.lastIndexOf('file');
  return visualWidth(line.slice(0, index));
}
