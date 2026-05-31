import { accent, bold, dim, error, success } from '../terminal/shared/ansi.js';
import { padPlain, truncatePlain, visualWidth } from '../terminal/shared/text.js';
import { block, line, type CardRow } from '../terminal/render/renderPrimitives.js';
import type { RenderBlock, RenderedLine, TerminalView } from '../terminal/render/viewModel.js';

export type ConfigSetupFieldId = 'providerId' | 'baseUrl' | 'apiKey' | 'modelId' | 'contextWindowK';

export type ConfigSetupField = {
  id: ConfigSetupFieldId;
  label: string;
  value: string;
  placeholder: string;
  masked?: boolean;
  suffix?: string;
};

export type ConfigSetupRenderState = {
  fields: ConfigSetupField[];
  activeIndex: number;
  cursorOffset: number;
  error?: string;
  configPath: string;
};

const CARD_INNER_WIDTH = 64;

export function renderConfigSetupView(
  state: ConfigSetupRenderState,
  columns: number,
): TerminalView {
  const width = Math.max(20, columns - 1);
  const card = renderConfigCard(state, width);
  const history = block('setup-history');
  const active = block('setup-active', [card]);
  return {
    resetKey: 'config-setup',
    frame: block('setup-frame', [history, active]),
    history,
    active,
    cursor: card.cursor,
  };
}

export function renderConfigCard(state: ConfigSetupRenderState, width: number): RenderBlock {
  const innerWidth = Math.max(18, Math.min(CARD_INNER_WIDTH, width - 4));
  const rows: CardRow[] = [
    { raw: 'Aether setup', text: accent(bold('Aether setup')) },
    { raw: `Create ${state.configPath}`, text: dim(`Create ${state.configPath}`) },
    { raw: 'Only for OpenAI Responses API', text: dim('Only for OpenAI Responses API') },
    { raw: '', text: '' },
  ];

  const fieldRowsStart = rows.length;
  const labelWidth = fieldLabelWidth(state.fields);
  const valueStart = 3 + labelWidth;
  for (const [index, field] of state.fields.entries()) {
    rows.push(renderFieldRow(field, index === state.activeIndex, innerWidth, labelWidth));
  }

  rows.push({ raw: '', text: '' });
  if (state.error) {
    rows.push({ raw: state.error, text: error(state.error) });
  }
  rows.push({
    raw: 'Enter next/save - Up/Down switch - Esc cancel',
    text: dim('Enter next/save - Up/Down switch - Esc cancel'),
  });

  const lines = borderedRows(rows, width, innerWidth);
  const activeField = state.fields[state.activeIndex];
  const activeDisplayValue = activeField ? displayValue(activeField) : '';
  const activeVisibleValue = activeDisplayValue || activeField?.placeholder || '';
  const suffixWidth = activeField?.suffix ? visualWidth(fieldSuffix(activeField, activeVisibleValue)) : 0;
  const valueWidth = Math.max(0, innerWidth - valueStart - suffixWidth);
  const cursorOffset = Math.min(
    visualWidth(activeDisplayValue),
    Math.max(0, state.cursorOffset),
    valueWidth,
  );
  const cursor = {
    x: 2 + valueStart + cursorOffset,
    y: 1 + fieldRowsStart + state.activeIndex,
  };
  return block('config-setup-card', lines, cursor);
}

function renderFieldRow(field: ConfigSetupField, active: boolean, innerWidth: number, labelWidth: number): CardRow {
  const marker = active ? '>' : ' ';
  const label = padPlain(field.label, labelWidth);
  const value = displayValue(field) || field.placeholder;
  const rawPrefix = `${marker} ${label} `;
  const suffix = fieldSuffix(field, value);
  const valueWidth = Math.max(0, innerWidth - visualWidth(rawPrefix) - visualWidth(suffix));
  const rawValue = truncatePlain(value, valueWidth);
  const raw = `${rawPrefix}${rawValue}${suffix}`;
  const styledMarker = active ? accent(marker) : dim(marker);
  const styledLabel = active ? success(label) : label;
  const styledValue = displayValue(field) ? rawValue : dim(rawValue);
  return { raw, text: `${styledMarker} ${styledLabel} ${styledValue}${field.suffix ? dim(suffix) : ''}` };
}

function displayValue(field: ConfigSetupField): string {
  if (!field.masked) {
    return field.value;
  }
  return field.value ? '*'.repeat(Math.min(field.value.length, 32)) : '';
}

function fieldLabelWidth(fields: ConfigSetupField[]): number {
  return Math.max(8, ...fields.map(field => visualWidth(field.label)));
}

function fieldSuffix(field: ConfigSetupField, value: string): string {
  if (!field.suffix) {
    return '';
  }
  return value ? ` ${field.suffix}` : ` ${field.suffix}`;
}

function borderedRows(rows: CardRow[], width: number, innerWidth: number): RenderedLine[] {
  const contentWidth = Math.max(0, Math.min(innerWidth, Math.max(...rows.map(row => visualWidth(row.raw)))));
  const borderWidth = contentWidth + 2;
  const top = `╭${'─'.repeat(borderWidth)}╮`;
  const bottom = `╰${'─'.repeat(borderWidth)}╯`;
  const output: RenderedLine[] = [line(dim(top), top, width, 'config-card')];

  for (const row of rows) {
    const fittedRaw = truncatePlain(row.raw, contentWidth);
    const fittedText = visualWidth(row.raw) > contentWidth ? fittedRaw : row.text;
    const padding = ' '.repeat(Math.max(0, contentWidth - visualWidth(fittedRaw)));
    const raw = `│ ${fittedRaw}${padding} │`;
    const text = `${dim('│ ')}${fittedText}${dim(`${padding} │`)}`;
    output.push(line(text, raw, width, 'config-card'));
  }

  output.push(line(dim(bottom), bottom, width, 'config-card'));
  return output;
}
