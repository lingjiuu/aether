import { describe, expect, it } from 'vitest';
import { initialState, reducer, type AppAction, type AppState } from '../../state/reducer.js';
import { ComposerController } from '../input/composerController.js';

describe('ComposerController', () => {
  it('owns cursor movement and text insertion', () => {
    let state: AppState = initialState;
    const composer = new ComposerController();
    const dispatch = (action: AppAction) => {
      state = reducer(state, action);
      composer.syncValue(state.composer.value);
    };

    composer.setValue('abc', dispatch);
    expect(composer.cursorOffset).toBe(3);

    expect(composer.handleKey({ kind: 'left' }, state, { dispatch, submit: () => {} })).toBe(true);
    expect(composer.cursorOffset).toBe(2);

    expect(composer.handleKey({ kind: 'text', value: 'X' }, state, { dispatch, submit: () => {} })).toBe(false);
    expect(state.composer.value).toBe('abXc');
    expect(composer.cursorOffset).toBe(3);
  });

  it('submits trimmed composer text', () => {
    let state: AppState = initialState;
    const composer = new ComposerController();
    const dispatch = (action: AppAction) => {
      state = reducer(state, action);
      composer.syncValue(state.composer.value);
    };
    const submitted: string[] = [];

    composer.setValue('  hello  ', dispatch);
    expect(composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) })).toBe(false);

    expect(submitted).toEqual(['hello']);
  });
});
