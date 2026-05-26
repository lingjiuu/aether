import path from 'node:path';
import {
  getSlashCommandInsertText,
  getSlashCommandSuggestions,
  type SlashCommandInfo,
} from '../../commands/slashCommands.js';
import { searchFiles, type FileSuggestion } from '../../domain/fileSearch.js';
import type { TurnSubmission } from '../../app/runtime.js';
import type { SkillInfo, TurnInputItem } from '../../protocol/wire.js';
import type { AppAction, AppState } from '../../state/reducer.js';
import { clampIndex, clampNumber } from '../shared/terminalMath.js';
import type { Key } from './inputParser.js';

type ComposerCallbacks = {
  dispatch: (action: AppAction) => void;
  submit: (submission: TurnSubmission) => void;
};

type TokenRange = {
  kind: 'skill' | 'file';
  query: string;
  start: number;
  end: number;
};

type SelectedSkill = {
  name?: string | null;
  path?: string | null;
};

type SelectedImage = {
  path: string;
  displayPath: string;
};

export class ComposerController {
  private cursor = 0;
  private readonly selectedSkills = new Map<string, SelectedSkill>();
  private readonly selectedImages = new Map<string, SelectedImage>();

  get cursorOffset(): number {
    return this.cursor;
  }

  setValue(value: string, dispatch: (action: AppAction) => void, cursorOffset = value.length): void {
    this.cursor = clampNumber(cursorOffset, 0, value.length);
    if (!value) {
      this.selectedSkills.clear();
      this.selectedImages.clear();
    }
    dispatch({ type: 'composerChanged', value });
  }

  syncValue(value: string): void {
    this.cursor = clampNumber(this.cursor, 0, value.length);
  }

  handleKey(key: Key, state: AppState, callbacks: ComposerCallbacks): boolean {
    const value = state.composer.value;
    const suggestions = this.suggestions(state);
    const selectedSuggestion = suggestions[clampIndex(state.composer.selectedSuggestionIndex, suggestions.length)];
    const popupItems = state.composer.popup?.items ?? [];
    const selectedPopupItem = popupItems[clampIndex(state.composer.selectedSuggestionIndex, popupItems.length)];
    const cursorOffset = clampNumber(this.cursor, 0, value.length);

    switch (key.kind) {
      case 'escape':
        if (value) {
          this.updateValue('', state, callbacks.dispatch);
        }
        return false;
      case 'up':
        if (popupItems.length) {
          callbacks.dispatch({ type: 'composerSuggestionMoved', delta: -1, count: popupItems.length });
          return false;
        }
        if (suggestions.length) {
          callbacks.dispatch({ type: 'composerSuggestionMoved', delta: -1, count: suggestions.length });
        }
        return false;
      case 'down':
        if (popupItems.length) {
          callbacks.dispatch({ type: 'composerSuggestionMoved', delta: 1, count: popupItems.length });
          return false;
        }
        if (suggestions.length) {
          callbacks.dispatch({ type: 'composerSuggestionMoved', delta: 1, count: suggestions.length });
        }
        return false;
      case 'left':
        this.cursor = clampNumber(cursorOffset - 1, 0, value.length);
        callbacks.dispatch({ type: 'composerPopupChanged', popup: this.popup(value, state) });
        return true;
      case 'right':
        this.cursor = clampNumber(cursorOffset + 1, 0, value.length);
        callbacks.dispatch({ type: 'composerPopupChanged', popup: this.popup(value, state) });
        return true;
      case 'ctrl-a':
      case 'home':
        this.cursor = 0;
        callbacks.dispatch({ type: 'composerPopupChanged', popup: this.popup(value, state) });
        return true;
      case 'ctrl-e':
      case 'end':
        this.cursor = value.length;
        callbacks.dispatch({ type: 'composerPopupChanged', popup: this.popup(value, state) });
        return true;
      case 'backspace':
        if (cursorOffset > 0) {
          this.updateValue(value.slice(0, cursorOffset - 1) + value.slice(cursorOffset), state, callbacks.dispatch, cursorOffset - 1);
        }
        return false;
      case 'tab':
        if (state.composer.popup && selectedPopupItem) {
          this.insertPopupItem(state, selectedPopupItem, callbacks.dispatch);
          return false;
        }
        if (selectedSuggestion) {
          const nextValue = getSlashCommandInsertText(selectedSuggestion);
          this.updateValue(nextValue, state, callbacks.dispatch, nextValue.length);
        }
        return false;
      case 'return':
        if (state.composer.popup && selectedPopupItem) {
          this.insertPopupItem(state, selectedPopupItem, callbacks.dispatch);
          return false;
        }
        this.submitValue(value, state, selectedSuggestion, callbacks);
        return false;
      case 'text':
        this.updateValue(
          value.slice(0, cursorOffset) + key.value + value.slice(cursorOffset),
          state,
          callbacks.dispatch,
          cursorOffset + key.value.length,
        );
        return false;
      default:
        return false;
    }
  }

  private suggestions(state: AppState): SlashCommandInfo[] {
    return state.composer.commandPaletteOpen ? getSlashCommandSuggestions(state.composer.value) : [];
  }

  private submitValue(
    value: string,
    state: AppState,
    selectedSuggestion: SlashCommandInfo | undefined,
    callbacks: ComposerCallbacks,
  ): void {
    if (selectedSuggestion) {
      const text = getSlashCommandInsertText(selectedSuggestion);
      callbacks.submit({ text, items: [{ type: 'text', text }] });
      return;
    }

    const text = value.trim();
    if (text) {
      callbacks.submit({ text, items: [{ type: 'text', text }, ...this.extraItems(text, state)] });
    }
  }

  private updateValue(
    value: string,
    state: AppState,
    dispatch: (action: AppAction) => void,
    cursorOffset = value.length,
  ): void {
    this.cursor = clampNumber(cursorOffset, 0, value.length);
    if (!value) {
      this.selectedSkills.clear();
      this.selectedImages.clear();
    }
    dispatch({ type: 'composerChanged', value });
    dispatch({ type: 'composerPopupChanged', popup: this.popup(value, state) });
  }

  private popup(value: string, state: AppState) {
    const token = currentToken(value, this.cursor);
    if (!token) {
      return undefined;
    }
    if (token.kind === 'skill') {
      const items = filterSkills(state.skills, token.query);
      return items.length ? { kind: 'skill' as const, query: token.query, items } : undefined;
    }
    const items = searchFiles(state.session.cwd, token.query);
    return items.length ? { kind: 'file' as const, query: token.query, items } : undefined;
  }

  private insertPopupItem(state: AppState, item: SkillInfo | FileSuggestion, dispatch: (action: AppAction) => void): void {
    const token = currentToken(state.composer.value, this.cursor);
    if (!token) {
      return;
    }
    if (token.kind === 'skill') {
      const skill = item as SkillInfo;
      const name = skill.name?.trim();
      if (!name) {
        return;
      }
      this.selectedSkills.set(skillKey(skill), { name: skill.name, path: skill.location ?? skill.path });
      this.replaceToken(state, token, `$${name} `, dispatch);
      return;
    }

    const file = item as FileSuggestion;
    if (file.isImage) {
      this.selectedImages.set(file.path, { path: file.path, displayPath: file.displayPath });
    }
    this.replaceToken(state, token, `@${file.displayPath} `, dispatch);
  }

  private replaceToken(state: AppState, token: TokenRange, replacement: string, dispatch: (action: AppAction) => void): void {
    const value = state.composer.value;
    const nextValue = value.slice(0, token.start) + replacement + value.slice(token.end);
    this.updateValue(nextValue, state, dispatch, token.start + replacement.length);
  }

  private extraItems(text: string, state: AppState): TurnInputItem[] {
    const items: TurnInputItem[] = [];
    const seenSkills = new Set<string>();
    for (const skill of this.skillsMentionedIn(text, state.skills)) {
      const key = skillKey(skill);
      if (key && !seenSkills.has(key)) {
        seenSkills.add(key);
        items.push({ type: 'skill', name: skill.name, path: skill.path });
      }
    }

    const seenImages = new Set<string>();
    for (const image of this.imagesMentionedIn(text, state.session.cwd)) {
      if (!seenImages.has(image)) {
        seenImages.add(image);
        items.push({ type: 'localImage', path: image });
      }
    }
    return items;
  }

  private skillsMentionedIn(text: string, skills: SkillInfo[]): SelectedSkill[] {
    const selected = Array.from(this.selectedSkills.values())
      .filter(skill => skill.name ? mentionsSkill(text, skill.name) : Boolean(skill.path && text.includes(skill.path)));
    const typed = skills
      .filter(skill => skill.name && mentionsSkill(text, skill.name))
      .map(skill => ({ name: skill.name, path: skill.location ?? skill.path }));
    return [...selected, ...typed];
  }

  private imagesMentionedIn(text: string, cwd: string | undefined): string[] {
    const selected = Array.from(this.selectedImages.values())
      .filter(image => text.includes(`@${image.displayPath}`) || text.includes(image.path))
      .map(image => image.path);
    const typed = Array.from(text.matchAll(/(?:^|\s)@(\S+)/g))
      .map(match => match[1])
      .filter((inputPath): inputPath is string => Boolean(inputPath))
      .filter(isImagePath)
      .map(inputPath => resolveInputPath(cwd, inputPath));
    return [...selected, ...typed];
  }
}

function currentToken(value: string, cursorOffset: number): TokenRange | undefined {
  const cursor = clampNumber(cursorOffset, 0, value.length);
  const beforeCursor = value.slice(0, cursor);
  const start = Math.max(beforeCursor.lastIndexOf(' '), beforeCursor.lastIndexOf('\n'), beforeCursor.lastIndexOf('\t')) + 1;
  const token = beforeCursor.slice(start);
  if (!token || /\s/.test(token)) {
    return undefined;
  }
  if (token.startsWith('$')) {
    return { kind: 'skill', query: token.slice(1), start, end: cursor };
  }
  if (token.startsWith('@')) {
    return { kind: 'file', query: token.slice(1), start, end: cursor };
  }
  return undefined;
}

function filterSkills(skills: SkillInfo[], query: string, limit = 12): SkillInfo[] {
  const normalized = query.trim().toLowerCase();
  return skills
    .filter(skill => {
      const name = skill.name?.toLowerCase() ?? '';
      const description = skill.description?.toLowerCase() ?? '';
      return !normalized || name.includes(normalized) || description.includes(normalized);
    })
    .slice(0, limit);
}

function skillKey(skill: SkillInfo | SelectedSkill): string {
  return skill.name?.trim() || skill.path?.trim() || '';
}

function mentionsSkill(text: string, name: string): boolean {
  return new RegExp(`(^|\\s)\\$${escapeRegExp(name)}(?=$|\\s)`).test(text);
}

function isImagePath(inputPath: string): boolean {
  return ['.gif', '.jpg', '.jpeg', '.png', '.webp'].includes(path.extname(inputPath).toLowerCase());
}

function resolveInputPath(cwd: string | undefined, inputPath: string): string {
  return path.isAbsolute(inputPath) ? inputPath : path.resolve(cwd ?? process.cwd(), inputPath);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
