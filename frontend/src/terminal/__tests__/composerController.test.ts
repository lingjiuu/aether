import { describe, expect, it } from 'vitest';
import { mkdirSync, writeFileSync } from 'node:fs';
import { mkdtempSync } from 'node:fs';
import path from 'node:path';
import { tmpdir } from 'node:os';
import type { TurnSubmission } from '../../app/runtime.js';
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
    const submitted: TurnSubmission[] = [];

    composer.setValue('  hello  ', dispatch);
    expect(composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) })).toBe(false);

    expect(submitted).toEqual([
      {
        text: 'hello',
        items: [{ type: 'text', text: 'hello' }],
      },
    ]);
  });

  it('adds selected skill items to the submitted turn', () => {
    let state: AppState = reducer(initialState, {
      type: 'skillsLoaded',
      skills: [
        {
          name: 'demo',
          description: 'Demo skill',
          location: '/repo/.aether/skills/demo/SKILL.md',
        },
      ],
    });
    const composer = new ComposerController();
    const dispatch = (action: AppAction) => {
      state = reducer(state, action);
      composer.syncValue(state.composer.value);
    };
    const submitted: TurnSubmission[] = [];

    composer.setValue('$de', dispatch);
    composer.handleKey({ kind: 'text', value: 'm' }, state, { dispatch, submit: input => submitted.push(input) });
    expect(state.composer.popup?.kind).toBe('skill');

    composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) });
    expect(state.composer.value).toBe('$demo ');

    composer.setValue('Use $demo', dispatch);
    composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) });

    expect(submitted).toEqual([
      {
        text: 'Use $demo',
        items: [
          { type: 'text', text: 'Use $demo' },
          { type: 'skill', name: 'demo', path: '/repo/.aether/skills/demo/SKILL.md' },
        ],
      },
    ]);
  });

  it('adds selected local images but keeps ordinary files as text', () => {
    const cwd = mkdtempSync(path.join(tmpdir(), 'aether-composer-'));
    mkdirSync(path.join(cwd, 'docs'));
    writeFileSync(path.join(cwd, 'docs', 'note.md'), 'hello');
    writeFileSync(path.join(cwd, 'pixel.png'), 'not a real image');
    let state: AppState = {
      ...initialState,
      session: {
        ...initialState.session,
        cwd,
      },
    };
    const composer = new ComposerController();
    const dispatch = (action: AppAction) => {
      state = reducer(state, action);
      composer.syncValue(state.composer.value);
    };
    const submitted: TurnSubmission[] = [];

    composer.setValue('@note', dispatch);
    composer.handleKey({ kind: 'text', value: '.' }, state, { dispatch, submit: input => submitted.push(input) });
    composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) });
    expect(state.composer.value).toBe('@docs/note.md ');

    composer.setValue('@pixel', dispatch);
    composer.handleKey({ kind: 'text', value: '.' }, state, { dispatch, submit: input => submitted.push(input) });
    composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) });
    expect(state.composer.value).toBe('@pixel.png ');

    composer.setValue('Compare @docs/note.md and @pixel.png', dispatch);
    composer.handleKey({ kind: 'return' }, state, { dispatch, submit: input => submitted.push(input) });

    expect(submitted).toEqual([
      {
        text: 'Compare @docs/note.md and @pixel.png',
        items: [
          { type: 'text', text: 'Compare @docs/note.md and @pixel.png' },
          { type: 'localImage', path: path.join(cwd, 'pixel.png') },
        ],
      },
    ]);
  });
});
