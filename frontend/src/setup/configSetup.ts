import { aetherConfigPath, hasAetherConfig, writeAetherConfig, type AetherConfigSetupValues } from './aetherConfig.js';
import { KeyParser, type Key } from '../terminal/input/inputParser.js';
import { TerminalRuntime } from '../terminal/app/TerminalRuntime.js';
import { TerminalWriter } from '../terminal/app/TerminalWriter.js';
import { renderConfigSetupView, type ConfigSetupField, type ConfigSetupFieldId } from './configSetupRenderer.js';

type ConfigSetupOptions = {
  stdin?: NodeJS.ReadStream;
  stdout?: NodeJS.WriteStream;
  configPath?: string;
  env?: NodeJS.ProcessEnv;
};

type ConfigSetupState = {
  fields: ConfigSetupField[];
  activeIndex: number;
  cursorOffsets: Record<ConfigSetupFieldId, number>;
  error?: string;
};

export async function ensureAetherConfig({
  stdin = process.stdin,
  stdout = process.stdout,
  configPath = aetherConfigPath(),
  env = process.env,
}: ConfigSetupOptions = {}): Promise<boolean> {
  if (hasAetherConfig(configPath)) {
    return true;
  }
  if (!stdin.isTTY || !stdout.isTTY) {
    throw new Error(`Aether config not found: ${configPath}. Run Aether interactively once to create it.`);
  }
  return runConfigSetup({ stdin, stdout, configPath, env });
}

async function runConfigSetup({
  stdin,
  stdout,
  configPath,
  env,
}: Required<ConfigSetupOptions>): Promise<boolean> {
  const writer = new TerminalWriter(stdout);
  const keyParser = new KeyParser();
  let state = initialState(env);
  let done = false;
  let resolved = false;

  return new Promise<boolean>(resolve => {
    const render = () => {
      writer.render(renderConfigSetupView(renderState(state, configPath), stdout.columns ?? 80));
    };
    const finish = (value: boolean) => {
      if (done) {
        return;
      }
      done = true;
      resolved = value;
      runtime.stop();
      writer.stop();
      resolve(value);
    };
    const onData = (chunk: string | Buffer) => {
      for (const key of keyParser.parse(chunk)) {
        void handleKey(key, state, {
          configPath,
          render,
          finish,
          update: next => {
            state = next;
            render();
          },
        });
      }
    };
    const runtime = new TerminalRuntime({
      stdin,
      stdout,
      onData,
      onResize: render,
      onStop: () => finish(false),
      onExit: () => {
        if (!resolved) {
          writer.stop();
        }
      },
    });

    writer.start();
    runtime.start();
    render();
  });
}

type KeyCallbacks = {
  configPath: string;
  render: () => void;
  finish: (value: boolean) => void;
  update: (state: ConfigSetupState) => void;
};

async function handleKey(key: Key, state: ConfigSetupState, callbacks: KeyCallbacks): Promise<void> {
  switch (key.kind) {
    case 'ctrl-c':
    case 'escape':
      callbacks.finish(false);
      return;
    case 'up':
      callbacks.update(moveActive(state, -1));
      return;
    case 'down':
    case 'tab':
      callbacks.update(moveActive(state, 1));
      return;
    case 'left':
      callbacks.update(moveCursor(state, -1));
      return;
    case 'right':
      callbacks.update(moveCursor(state, 1));
      return;
    case 'home':
    case 'ctrl-a':
      callbacks.update(setCursor(state, 0));
      return;
    case 'end':
    case 'ctrl-e':
      callbacks.update(setCursor(state, activeField(state).value.length));
      return;
    case 'backspace':
      callbacks.update(deleteBeforeCursor(state));
      return;
    case 'text':
      callbacks.update(insertText(state, key.value));
      return;
    case 'return':
      await submitOrAdvance(state, callbacks);
      return;
    default:
      return;
  }
}

async function submitOrAdvance(state: ConfigSetupState, callbacks: KeyCallbacks): Promise<void> {
  const validationError = validate(state);
  if (validationError) {
    if (state.activeIndex < state.fields.length - 1) {
      callbacks.update(moveActive(state, 1));
    } else {
      callbacks.update({ ...state, error: validationError });
    }
    return;
  }
  if (state.activeIndex < state.fields.length - 1) {
    callbacks.update(moveActive(state, 1));
    return;
  }

  try {
    await writeAetherConfig(valuesFromState(state), callbacks.configPath);
    callbacks.finish(true);
  } catch (error) {
    if (isFileExistsError(error)) {
      callbacks.finish(true);
      return;
    }
    callbacks.update({
      ...state,
      error: error instanceof Error ? error.message : String(error),
    });
  }
}

function initialState(env: NodeJS.ProcessEnv): ConfigSetupState {
  const baseUrl = env.AETHER_MODEL_BASE_URL?.trim()
    || env.OPENAI_BASE_URL?.trim()
    || '';
  const apiKey = env.OPENAI_API_KEY?.trim() || '';
  const providerId = env.AETHER_PROVIDER_ID?.trim() || '';
  const modelId = env.AETHER_MODEL_ID?.trim()
    || env.AETHER_EVAL_MODEL_ID?.trim()
    || 'gpt-5.5';
  const fields: ConfigSetupField[] = [
    { id: 'providerId', label: 'Provider', value: providerId, placeholder: '' },
    { id: 'baseUrl', label: 'Base URL', value: baseUrl, placeholder: '' },
    { id: 'apiKey', label: 'API key', value: apiKey, placeholder: 'sk-...', masked: true },
    { id: 'modelId', label: 'Model', value: modelId, placeholder: 'gpt-5.5' },
  ];
  const firstEmptyIndex = fields.findIndex(field => !field.value.trim());
  return {
    fields,
    activeIndex: firstEmptyIndex >= 0 ? firstEmptyIndex : fields.length - 1,
    cursorOffsets: {
      providerId: providerId.length,
      baseUrl: baseUrl.length,
      apiKey: apiKey.length,
      modelId: modelId.length,
    },
  };
}

function renderState(state: ConfigSetupState, configPath: string) {
  return {
    fields: state.fields,
    activeIndex: state.activeIndex,
    cursorOffset: state.cursorOffsets[activeField(state).id],
    error: state.error,
    configPath,
  };
}

function moveActive(state: ConfigSetupState, delta: -1 | 1): ConfigSetupState {
  const nextIndex = wrapIndex(state.activeIndex + delta, state.fields.length);
  return { ...state, activeIndex: nextIndex, error: undefined };
}

function moveCursor(state: ConfigSetupState, delta: -1 | 1): ConfigSetupState {
  const field = activeField(state);
  return setCursor(state, state.cursorOffsets[field.id] + delta);
}

function setCursor(state: ConfigSetupState, offset: number): ConfigSetupState {
  const field = activeField(state);
  return {
    ...state,
    cursorOffsets: {
      ...state.cursorOffsets,
      [field.id]: clamp(offset, 0, field.value.length),
    },
  };
}

function insertText(state: ConfigSetupState, text: string): ConfigSetupState {
  const field = activeField(state);
  const cursor = state.cursorOffsets[field.id];
  const value = `${field.value.slice(0, cursor)}${text}${field.value.slice(cursor)}`;
  return updateFieldValue(state, field.id, value, cursor + text.length);
}

function deleteBeforeCursor(state: ConfigSetupState): ConfigSetupState {
  const field = activeField(state);
  const cursor = state.cursorOffsets[field.id];
  if (cursor <= 0) {
    return state;
  }
  const value = `${field.value.slice(0, cursor - 1)}${field.value.slice(cursor)}`;
  return updateFieldValue(state, field.id, value, cursor - 1);
}

function updateFieldValue(
  state: ConfigSetupState,
  id: ConfigSetupFieldId,
  value: string,
  cursorOffset: number,
): ConfigSetupState {
  return {
    ...state,
    error: undefined,
    fields: state.fields.map(field => field.id === id ? { ...field, value } : field),
    cursorOffsets: {
      ...state.cursorOffsets,
      [id]: cursorOffset,
    },
  };
}

function validate(state: ConfigSetupState): string | undefined {
  const values = valuesFromState(state);
  if (!values.providerId) {
    return 'Provider is required.';
  }
  if (!values.baseUrl) {
    return 'Base URL is required.';
  }
  try {
    const url = new URL(values.baseUrl);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return 'Base URL must start with http:// or https://.';
    }
  } catch {
    return 'Base URL must be a valid URL.';
  }
  if (!values.apiKey) {
    return 'API key is required.';
  }
  if (!values.modelId) {
    return 'Model is required.';
  }
  return undefined;
}

function valuesFromState(state: ConfigSetupState): AetherConfigSetupValues {
  return {
    providerId: fieldValue(state, 'providerId'),
    baseUrl: fieldValue(state, 'baseUrl'),
    apiKey: fieldValue(state, 'apiKey'),
    modelId: fieldValue(state, 'modelId'),
  };
}

function fieldValue(state: ConfigSetupState, id: ConfigSetupFieldId): string {
  return state.fields.find(field => field.id === id)?.value.trim() ?? '';
}

function activeField(state: ConfigSetupState): ConfigSetupField {
  return state.fields[state.activeIndex] ?? state.fields[0]!;
}

function wrapIndex(index: number, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return ((index % count) + count) % count;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function isFileExistsError(error: unknown): boolean {
  return typeof error === 'object'
    && error !== null
    && 'code' in error
    && (error as { code?: unknown }).code === 'EEXIST';
}
