import {
  getSlashCommandInsertText,
  getSlashCommandSuggestions,
  type SlashCommandInfo,
} from '../../commands/slashCommands.js';
import type { AppAction, AppState } from '../../state/reducer.js';
import type { Key } from './inputParser.js';
import { clampIndex, clampNumber } from '../shared/terminalMath.js';

type ComposerCallbacks = {
  dispatch: (action: AppAction) => void;
  submit: (input: string) => void;
};

export class ComposerController {
  private cursor = 0;

  get cursorOffset(): number {
    return this.cursor;
  }

  setValue(value: string, dispatch: (action: AppAction) => void, cursorOffset = value.length): void {
    this.cursor = clampNumber(cursorOffset, 0, value.length);
    dispatch({ type: 'composerChanged', value });
  }

  syncValue(value: string): void {
    this.cursor = clampNumber(this.cursor, 0, value.length);
  }

  handleKey(key: Key, state: AppState, callbacks: ComposerCallbacks): boolean {
    const value = state.composer.value;
    const suggestions = this.suggestions(state);
    const selectedSuggestion = suggestions[clampIndex(state.composer.selectedSuggestionIndex, suggestions.length)];
    const cursorOffset = clampNumber(this.cursor, 0, value.length);

    switch (key.kind) {
      case 'escape':
        if (value) {
          this.setValue('', callbacks.dispatch);
        }
        return false;
      case 'up':
        if (suggestions.length) {
          callbacks.dispatch({ type: 'composerSuggestionMoved', delta: -1, count: suggestions.length });
        }
        return false;
      case 'down':
        if (suggestions.length) {
          callbacks.dispatch({ type: 'composerSuggestionMoved', delta: 1, count: suggestions.length });
        }
        return false;
      case 'left':
        this.cursor = clampNumber(cursorOffset - 1, 0, value.length);
        return true;
      case 'right':
        this.cursor = clampNumber(cursorOffset + 1, 0, value.length);
        return true;
      case 'ctrl-a':
      case 'home':
        this.cursor = 0;
        return true;
      case 'ctrl-e':
      case 'end':
        this.cursor = value.length;
        return true;
      case 'backspace':
        if (cursorOffset > 0) {
          this.setValue(value.slice(0, cursorOffset - 1) + value.slice(cursorOffset), callbacks.dispatch, cursorOffset - 1);
        }
        return false;
      case 'tab':
        if (selectedSuggestion) {
          const nextValue = getSlashCommandInsertText(selectedSuggestion);
          this.setValue(nextValue, callbacks.dispatch, nextValue.length);
        }
        return false;
      case 'return':
        this.submitValue(value, selectedSuggestion, callbacks);
        return false;
      case 'text':
        this.setValue(value.slice(0, cursorOffset) + key.value + value.slice(cursorOffset), callbacks.dispatch, cursorOffset + key.value.length);
        return false;
      default:
        return false;
    }
  }

  private suggestions(state: AppState): SlashCommandInfo[] {
    return state.composer.commandPaletteOpen ? getSlashCommandSuggestions(state.composer.value) : [];
  }

  private submitValue(value: string, selectedSuggestion: SlashCommandInfo | undefined, callbacks: ComposerCallbacks): void {
    if (selectedSuggestion) {
      callbacks.submit(getSlashCommandInsertText(selectedSuggestion));
      return;
    }
    if (value.trim()) {
      callbacks.submit(value.trim());
    }
  }
}
