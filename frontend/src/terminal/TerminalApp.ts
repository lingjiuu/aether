import type { AetherClient } from '../backend/AetherClient.js';
import {
  getSlashCommandInsertText,
  getSlashCommandSuggestions,
  type SlashCommandInfo,
} from '../commands/slashCommands.js';
import { boot, handleInput } from '../app/runtime.js';
import { initialState, reducer, type AppAction, type AppState } from '../state/reducer.js';
import { ansi } from './ansi.js';
import { renderScrollback, type TerminalScrollInfo } from './renderScrollback.js';
import { charWidth } from './text.js';
import { TerminalWriter } from './TerminalWriter.js';

export type Key =
  | { kind: 'text'; value: string }
  | { kind: 'return' }
  | { kind: 'tab' }
  | { kind: 'backspace' }
  | { kind: 'escape' }
  | { kind: 'up' }
  | { kind: 'down' }
  | { kind: 'left' }
  | { kind: 'right' }
  | { kind: 'page-up' }
  | { kind: 'page-down' }
  | { kind: 'home' }
  | { kind: 'end' }
  | { kind: 'wheel-up' }
  | { kind: 'wheel-down' }
  | { kind: 'ctrl-c' }
  | { kind: 'ctrl-a' }
  | { kind: 'ctrl-e' };

export class TerminalApp {
  private state: AppState = initialState;
  private readonly writer: TerminalWriter;
  private composerCursorOffset = 0;
  private approvalSelectedIndex = 0;
  private approvalId?: string;
  private transcriptScrollTop: number | undefined;
  private lastScroll: TerminalScrollInfo = {
    scrollTop: 0,
    maxScrollTop: 0,
    viewportRows: 1,
    transcriptLineCount: 0,
    isAtBottom: true,
  };
  private stopped = false;
  private renderTimer?: NodeJS.Timeout;
  private terminalModesEnabled = false;

  constructor(
    private readonly client: AetherClient,
    private readonly stdin: NodeJS.ReadStream = process.stdin,
    private readonly stdout: NodeJS.WriteStream = process.stdout,
  ) {
    this.writer = new TerminalWriter(stdout);
  }

  async start(): Promise<void> {
    this.writer.start();
    this.bindTerminal();
    await boot(this.client, action => this.dispatch(action));
    this.render();
    this.renderTimer = setInterval(() => this.render(), 480);
  }

  stop(): void {
    if (this.stopped) {
      return;
    }
    this.stopped = true;
    if (this.renderTimer) {
      clearInterval(this.renderTimer);
    }
    this.stdin.off('data', this.onData);
    this.stdout.off('resize', this.onResize);
    this.disableTerminalModes();
    if (this.stdin.isTTY) {
      this.stdin.setRawMode(false);
    }
    this.stdin.pause();
    this.client.close();
    this.writer.stop();
  }

  private bindTerminal(): void {
    this.stdin.setEncoding('utf8');
    if (this.stdin.isTTY) {
      this.stdin.setRawMode(true);
    }
    this.enableTerminalModes();
    this.stdin.resume();
    this.stdin.on('data', this.onData);
    this.stdout.on('resize', this.onResize);
    process.once('SIGINT', () => this.stop());
    process.once('SIGTERM', () => this.stop());
    process.once('exit', () => {
      this.disableTerminalModes();
      this.writer.stop();
    });
  }

  private readonly onResize = (): void => {
    this.render();
  };

  private readonly onData = (chunk: string | Buffer): void => {
    const text = String(chunk);
    for (const key of parseKeys(text)) {
      void this.handleKey(key);
    }
  };

  private async handleKey(key: Key): Promise<void> {
    if (key.kind === 'ctrl-c') {
      this.stop();
      return;
    }

    if (this.handleScrollKey(key)) {
      return;
    }

    if (this.state.commandPanel) {
      await this.handlePanelKey(key);
      return;
    }

    if (this.state.pendingApproval) {
      await this.handleApprovalKey(key);
      return;
    }

    this.handleComposerKey(key);
  }

  private async handlePanelKey(key: Key): Promise<void> {
    const panel = this.state.commandPanel;
    if (!panel) {
      return;
    }

    if (key.kind === 'escape') {
      this.dispatch({
        type: 'commandPanelClosed',
        output: panel.kind === 'help' ? 'Help dialog dismissed' : 'Resume cancelled',
      });
      return;
    }

    if (panel.kind !== 'resume') {
      return;
    }

    const sessions = filterSessions(panel.sessions, panel.query);
    switch (key.kind) {
      case 'up':
        this.dispatch({ type: 'commandPanelSelectionMoved', delta: -1, count: sessions.length });
        break;
      case 'down':
        this.dispatch({ type: 'commandPanelSelectionMoved', delta: 1, count: sessions.length });
        break;
      case 'backspace':
        this.dispatch({ type: 'commandPanelQueryChanged', query: panel.query.slice(0, -1) });
        break;
      case 'return': {
        const selected = sessions[clampIndex(panel.selectedIndex, sessions.length)];
        if (selected?.sessionId) {
          await this.resumeSession(selected.sessionId);
        }
        break;
      }
      case 'text':
        this.dispatch({ type: 'commandPanelQueryChanged', query: `${panel.query}${key.value}` });
        break;
      default:
        break;
    }
  }

  private handleComposerKey(key: Key): void {
    const value = this.state.composer.value;
    const suggestions = this.suggestions();
    const selectedSuggestion = suggestions[clampIndex(this.state.composer.selectedSuggestionIndex, suggestions.length)];
    const cursorOffset = clampNumber(this.composerCursorOffset, 0, value.length);

    switch (key.kind) {
      case 'escape':
        if (value) {
          this.updateComposer('');
        }
        break;
      case 'up':
        if (suggestions.length) {
          this.dispatch({ type: 'composerSuggestionMoved', delta: -1, count: suggestions.length });
        }
        break;
      case 'down':
        if (suggestions.length) {
          this.dispatch({ type: 'composerSuggestionMoved', delta: 1, count: suggestions.length });
        }
        break;
      case 'left':
        this.composerCursorOffset = clampNumber(cursorOffset - 1, 0, value.length);
        this.render();
        break;
      case 'right':
        this.composerCursorOffset = clampNumber(cursorOffset + 1, 0, value.length);
        this.render();
        break;
      case 'ctrl-a':
      case 'home':
        this.composerCursorOffset = 0;
        this.render();
        break;
      case 'ctrl-e':
      case 'end':
        this.composerCursorOffset = value.length;
        this.render();
        break;
      case 'backspace':
        if (cursorOffset > 0) {
          this.updateComposer(value.slice(0, cursorOffset - 1) + value.slice(cursorOffset), cursorOffset - 1);
        }
        break;
      case 'tab':
        if (selectedSuggestion) {
          const nextValue = getSlashCommandInsertText(selectedSuggestion);
          this.updateComposer(nextValue, nextValue.length);
        }
        break;
      case 'return':
        if (selectedSuggestion) {
          void this.submitCommandSuggestion(selectedSuggestion);
          return;
        }
        if (value.trim()) {
          void this.submit(value.trim());
        }
        break;
      case 'text': {
        const nextValue = value.slice(0, cursorOffset) + key.value + value.slice(cursorOffset);
        this.updateComposer(nextValue, cursorOffset + key.value.length);
        break;
      }
      default:
        break;
    }
  }

  private async handleApprovalKey(key: Key): Promise<void> {
    switch (key.kind) {
      case 'up':
        this.approvalSelectedIndex = moveIndex(this.approvalSelectedIndex, -1, 2);
        this.render();
        break;
      case 'down':
        this.approvalSelectedIndex = moveIndex(this.approvalSelectedIndex, 1, 2);
        this.render();
        break;
      case 'return':
        await this.respondToApproval(this.approvalSelectedIndex === 0);
        break;
      case 'escape':
        await this.respondToApproval(false);
        break;
      default:
        break;
    }
  }

  private async respondToApproval(approved: boolean): Promise<void> {
    const approvalId = this.state.pendingApproval?.request.approvalId;
    if (!approvalId) {
      return;
    }
    try {
      await this.client.respondToApproval(approvalId, approved);
    } catch (error) {
      this.dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
    }
  }

  private async submitCommandSuggestion(suggestion: SlashCommandInfo): Promise<void> {
    const command = getSlashCommandInsertText(suggestion);
    this.updateComposer('');
    await this.submit(command);
  }

  private async submit(input: string): Promise<void> {
    this.transcriptScrollTop = undefined;
    this.updateComposer('');
    try {
      await handleInput(input, this.state, this.client, action => this.dispatch(action), () => this.stop());
    } catch (error) {
      this.dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
    }
  }

  private async resumeSession(sessionId: string): Promise<void> {
    try {
      const ack = await this.client.resume(sessionId);
      if (ack.history) {
        this.dispatch({ type: 'history', history: ack.history });
      } else {
        this.dispatch({ type: 'commandPanelClosed', output: ack.message ?? `Resumed ${sessionId}` });
      }
      if (ack.message) {
        this.dispatch({ type: 'notice', message: ack.message });
      }
      this.dispatch({ type: 'sessionState', session: await this.client.currentSession() });
    } catch (error) {
      this.dispatch({ type: 'notice', message: error instanceof Error ? error.message : String(error) });
    }
  }

  private updateComposer(value: string, cursorOffset = value.length): void {
    this.composerCursorOffset = clampNumber(cursorOffset, 0, value.length);
    this.dispatch({ type: 'composerChanged', value });
  }

  private suggestions(): SlashCommandInfo[] {
    return this.state.composer.commandPaletteOpen ? getSlashCommandSuggestions(this.state.composer.value) : [];
  }

  private dispatch(action: AppAction): void {
    if (action.type === 'history') {
      this.transcriptScrollTop = undefined;
    }
    this.state = reducer(this.state, action);
    this.composerCursorOffset = clampNumber(this.composerCursorOffset, 0, this.state.composer.value.length);
    this.syncApprovalSelection();
    this.render();
  }

  private render(): void {
    if (this.stopped) {
      return;
    }
    const view = renderScrollback({
      state: this.state,
      columns: this.stdout.columns ?? 80,
      rows: this.stdout.rows ?? 24,
      composerCursorOffset: this.composerCursorOffset,
      transcriptScrollTop: this.transcriptScrollTop,
      approvalSelectedIndex: this.approvalSelectedIndex,
    });
    this.lastScroll = view.scroll;
    if (this.transcriptScrollTop !== undefined) {
      this.transcriptScrollTop = view.scroll.isAtBottom ? undefined : view.scroll.scrollTop;
    }
    this.writer.render(view);
  }

  private syncApprovalSelection(): void {
    const approvalId = this.state.pendingApproval?.request.approvalId ?? undefined;
    if (approvalId !== this.approvalId) {
      this.approvalId = approvalId;
      this.approvalSelectedIndex = 0;
    }
  }

  private enableTerminalModes(): void {
    if (this.terminalModesEnabled || !this.stdout.isTTY) {
      return;
    }
    this.stdout.write(ansi.enableMouseTracking);
    this.terminalModesEnabled = true;
  }

  private disableTerminalModes(): void {
    if (!this.terminalModesEnabled) {
      return;
    }
    this.stdout.write(ansi.disableMouseTracking);
    this.terminalModesEnabled = false;
  }

  private handleScrollKey(key: Key): boolean {
    switch (key.kind) {
      case 'page-up':
        this.scrollTranscriptBy(-this.pageScrollRows());
        return true;
      case 'page-down':
        this.scrollTranscriptBy(this.pageScrollRows());
        return true;
      case 'home':
        if (this.state.composer.value || this.state.commandPanel) {
          return false;
        }
        this.scrollTranscriptToTop();
        return true;
      case 'end':
        if (this.state.composer.value || this.state.commandPanel) {
          return false;
        }
        this.scrollTranscriptToBottom();
        return true;
      case 'wheel-up':
        this.scrollTranscriptBy(-3);
        return true;
      case 'wheel-down':
        this.scrollTranscriptBy(3);
        return true;
      default:
        return false;
    }
  }

  private scrollTranscriptBy(deltaRows: number): void {
    const maxScrollTop = this.lastScroll.maxScrollTop;
    if (maxScrollTop <= 0) {
      return;
    }
    const currentTop = this.transcriptScrollTop ?? maxScrollTop;
    const nextTop = clampNumber(currentTop + deltaRows, 0, maxScrollTop);
    this.transcriptScrollTop = nextTop >= maxScrollTop ? undefined : nextTop;
    this.render();
  }

  private scrollTranscriptToTop(): void {
    if (this.lastScroll.maxScrollTop <= 0) {
      return;
    }
    this.transcriptScrollTop = 0;
    this.render();
  }

  private scrollTranscriptToBottom(): void {
    if (this.transcriptScrollTop === undefined) {
      return;
    }
    this.transcriptScrollTop = undefined;
    this.render();
  }

  private pageScrollRows(): number {
    return Math.max(1, this.lastScroll.viewportRows - 1);
  }
}

export function parseKeys(input: string): Key[] {
  const keys: Key[] = [];
  for (let index = 0; index < input.length; ) {
    const rest = input.slice(index);
    const mouse = rest.match(/^\x1b\[<(\d+);\d+;\d+[mM]/);
    if (mouse) {
      const buttonCode = Number(mouse[1]);
      const buttonWithoutModifiers = buttonCode & ~0b11100;
      if (buttonWithoutModifiers === 64) {
        keys.push({ kind: 'wheel-up' });
      } else if (buttonWithoutModifiers === 65) {
        keys.push({ kind: 'wheel-down' });
      }
      index += mouse[0].length;
    } else if (rest.startsWith('\x1b[A')) {
      keys.push({ kind: 'up' });
      index += 3;
    } else if (rest.startsWith('\x1b[B')) {
      keys.push({ kind: 'down' });
      index += 3;
    } else if (rest.startsWith('\x1b[C')) {
      keys.push({ kind: 'right' });
      index += 3;
    } else if (rest.startsWith('\x1b[D')) {
      keys.push({ kind: 'left' });
      index += 3;
    } else if (rest.startsWith('\x1b[5~')) {
      keys.push({ kind: 'page-up' });
      index += 4;
    } else if (rest.startsWith('\x1b[6~')) {
      keys.push({ kind: 'page-down' });
      index += 4;
    } else if (rest.startsWith('\x1b[H') || rest.startsWith('\x1b[1~') || rest.startsWith('\x1b[7~')) {
      keys.push({ kind: 'home' });
      index += rest.startsWith('\x1b[H') ? 3 : 4;
    } else if (rest.startsWith('\x1b[F') || rest.startsWith('\x1b[4~') || rest.startsWith('\x1b[8~')) {
      keys.push({ kind: 'end' });
      index += rest.startsWith('\x1b[F') ? 3 : 4;
    } else if (rest.startsWith('\x1b')) {
      keys.push({ kind: 'escape' });
      index += 1;
    } else {
      const char = Array.from(rest)[0] ?? '';
      index += char.length;
      switch (char) {
        case '\u0003':
          keys.push({ kind: 'ctrl-c' });
          break;
        case '\u0001':
          keys.push({ kind: 'ctrl-a' });
          break;
        case '\u0005':
          keys.push({ kind: 'ctrl-e' });
          break;
        case '\r':
        case '\n':
          keys.push({ kind: 'return' });
          break;
        case '\t':
          keys.push({ kind: 'tab' });
          break;
        case '\u007f':
        case '\b':
          keys.push({ kind: 'backspace' });
          break;
        default:
          if (charWidth(char) > 0) {
            keys.push({ kind: 'text', value: char });
          }
          break;
      }
    }
  }
  return keys;
}

function filterSessions(sessions: { name?: string | null; preview?: string | null; sessionId?: string | null; cwd?: string | null; modelId?: string | null }[], query: string) {
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

function clampIndex(index: number, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return clampNumber(index, 0, count - 1);
}

function moveIndex(current: number, delta: -1 | 1, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return (current + delta + count) % count;
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
