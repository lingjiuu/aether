import type { AetherClient } from '../../backend/AetherClient.js';
import { boot, handleInput } from '../../app/runtime.js';
import { initialState, reducer, type AppAction, type AppState } from '../../state/reducer.js';
import { ApprovalController } from '../interaction/approvalController.js';
import { CommandPanelController } from '../interaction/commandPanelController.js';
import { ComposerController } from '../input/composerController.js';
import { KeyParser, type Key } from '../input/inputParser.js';
import { createTerminalPresentation } from '../render/presentationModel.js';
import { renderScrollback } from '../render/renderScrollback.js';
import { RenderLoop } from './RenderLoop.js';
import { TerminalRuntime } from './TerminalRuntime.js';
import { TerminalWriter } from './TerminalWriter.js';
import { TranscriptViewportController } from '../viewport/viewportController.js';

export class TerminalApp {
  private state: AppState = initialState;
  private readonly writer: TerminalWriter;
  private readonly keyParser = new KeyParser();
  private readonly transcriptViewport = new TranscriptViewportController();
  private readonly composer = new ComposerController();
  private readonly commandPanel = new CommandPanelController();
  private readonly approval = new ApprovalController();
  private readonly runtime: TerminalRuntime;
  private readonly renderLoop: RenderLoop;
  private stopped = false;

  constructor(
    private readonly client: AetherClient,
    private readonly stdin: NodeJS.ReadStream = process.stdin,
    private readonly stdout: NodeJS.WriteStream = process.stdout,
  ) {
    this.writer = new TerminalWriter(stdout);
    this.runtime = new TerminalRuntime({
      stdin,
      stdout,
      onData: this.onData,
      onResize: this.onResize,
      onStop: () => this.stop(),
      onExit: () => this.writer.stop(),
    });
    this.renderLoop = new RenderLoop(() => this.render(), 480);
  }

  async start(): Promise<void> {
    this.writer.start();
    this.runtime.start();
    await boot(this.client, action => this.dispatch(action));
    this.render();
    this.renderLoop.start();
  }

  stop(): void {
    if (this.stopped) {
      return;
    }
    this.stopped = true;
    this.renderLoop.stop();
    this.runtime.stop();
    this.client.close();
    this.writer.stop();
  }

  private readonly onResize = (): void => {
    this.render();
  };

  private readonly onData = (chunk: string | Buffer): void => {
    for (const key of this.keyParser.parse(chunk)) {
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
      await this.commandPanel.handleKey(key, this.state, {
        dispatch: action => this.dispatch(action),
        resumeSession: sessionId => this.resumeSession(sessionId),
      });
      return;
    }

    if (this.state.pendingApproval) {
      await this.approval.handleKey(key, this.state, this.client, {
        dispatch: action => this.dispatch(action),
        render: () => this.render(),
      });
      return;
    }

    this.handleComposerKey(key);
  }

  private handleComposerKey(key: Key): void {
    const needsRender = this.composer.handleKey(key, this.state, {
      dispatch: action => this.dispatch(action),
      submit: input => {
        void this.submit(input);
      },
    });
    if (needsRender) {
      this.render();
    }
  }

  private async submit(input: string): Promise<void> {
    this.transcriptViewport.resetToBottom();
    this.composer.setValue('', action => this.dispatch(action));
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

  private dispatch(action: AppAction): void {
    if (action.type === 'history') {
      this.transcriptViewport.resetToBottom();
    }
    this.state = reducer(this.state, action);
    this.composer.syncValue(this.state.composer.value);
    this.approval.sync(this.state);
    this.render();
  }

  private render(): void {
    if (this.stopped) {
      return;
    }
    const view = renderScrollback({
      presentation: createTerminalPresentation(this.state),
      columns: this.stdout.columns ?? 80,
      rows: this.stdout.rows ?? 24,
      composerCursorOffset: this.composer.cursorOffset,
      transcriptScrollTop: this.transcriptViewport.renderScrollTop,
      pendingDeltaRows: this.transcriptViewport.pendingDelta,
      approvalSelectedIndex: this.approval.selection,
    });
    this.transcriptViewport.sync(view.scroll);
    this.writer.render(view);
  }

  private handleScrollKey(key: Key): boolean {
    const handled = this.transcriptViewport.handleKey(key, {
      composerHasValue: Boolean(this.state.composer.value),
      commandPanelOpen: Boolean(this.state.commandPanel),
    });
    if (handled) {
      this.render();
    }
    return handled;
  }
}
