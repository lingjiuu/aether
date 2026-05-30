import type { UiModelInfo, UiModelSelection } from '../../../protocol/wire.js';
import { selectableReasoningEfforts } from '../../../domain/modelCatalog.js';
import { accent, bold, dim, success } from '../../shared/ansi.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { padPlain, truncatePlain, visualWidth } from '../../shared/text.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, block, line } from '../renderPrimitives.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

const MODEL_VISIBLE_COUNT = 8;

export function renderModelPanel(presentation: TerminalPresentation, width: number, maxRows: number): RenderBlock {
  const panel = presentation.commandPanel;
  if (panel?.kind !== 'model') {
    return block('model-panel');
  }

  const models = panel.catalog.models ?? [];
  const customIndex = models.length;
  const rowCount = customIndex + 1;
  const selectedIndex = clampIndex(panel.selectedIndex, rowCount);
  let cursor: RenderBlock['cursor'];
  const lines: RenderedLine[] = [
    line(`  ${accent(bold('Select model'))}`, '  Select model', width),
    providerLine(panel, width),
    blankLine(),
  ];

  if (models.length) {
    const fixedRows = 10;
    const selectedModelIndex = Math.min(selectedIndex, Math.max(0, models.length - 1));
    const visibleCount = Math.max(1, Math.min(MODEL_VISIBLE_COUNT, maxRows - fixedRows, models.length));
    const visibleStart = Math.max(
      0,
      Math.min(
        Math.max(selectedModelIndex - Math.floor(visibleCount / 2), 0),
        Math.max(models.length - visibleCount, 0),
      ),
    );
    const visibleModels = models.slice(visibleStart, visibleStart + visibleCount);
    const labelWidth = modelLabelWidth(visibleModels, width);
    for (const [offset, model] of visibleModels.entries()) {
      const index = visibleStart + offset;
      lines.push(modelLine(model, panel.catalog.current, index, index === selectedIndex, labelWidth, width));
    }
  } else {
    lines.push(line(`  ${dim('No configured models')}`, '  No configured models', width));
  }

  lines.push(blankLine());
  lines.push(line(`  ${bold('Custom model')}`, '  Custom model', width));
  lines.push(customModelInputLine(panel, selectedIndex === customIndex, width));
  if (selectedIndex === customIndex) {
    cursor = { x: customModelCursorX(panel, width), y: lines.length - 1 };
  }

  lines.push(blankLine());
  const efforts = selectableReasoningEfforts(panel.catalog.reasoningEfforts);
  if (efforts.length) {
    lines.push(reasoningLine(efforts, panel.reasoningIndex, width));
    lines.push(blankLine());
  }
  lines.push(line(`  ${dim('↑/↓ select · Type custom · Enter confirm · Esc cancel')}`, '  ↑/↓ select · Type custom · Enter confirm · Esc cancel', width));
  return block('model-panel', lines, cursor);
}

function providerLine(panel: Extract<TerminalPresentation['commandPanel'], { kind: 'model' }>, width: number): RenderedLine {
  const provider = customProviderId(panel) ?? 'current';
  const raw = `  Provider: ${provider}`;
  return line(`  ${dim('Provider:')} ${provider}`, raw, width);
}

function modelLine(
  model: UiModelInfo,
  current: UiModelSelection | null | undefined,
  index: number,
  selected: boolean,
  labelWidth: number,
  width: number,
): RenderedLine {
  const marker = selected ? '❯ ' : '  ';
  const ordinal = `${index + 1}. `;
  const check = isCurrentModel(model, current) ? ' ✔' : '';
  const rawLabel = truncatePlain(`${modelLabel(model)}${check}`, labelWidth);
  const labelCell = padPlain(rawLabel, labelWidth);
  const raw = `${marker}${ordinal}${labelCell}`;
  const text = [
    selected ? accent(marker) : marker,
    ordinal,
    selected ? success(labelCell) : labelCell,
  ].join('');
  return line(text, raw, width);
}

function customModelInputLine(
  panel: Extract<TerminalPresentation['commandPanel'], { kind: 'model' }>,
  selected: boolean,
  width: number,
): RenderedLine {
  const marker = selected ? '❯ ' : '  ';
  const value = panel.customModel.trim();
  const placeholder = 'model id';
  const rawValue = truncatePlain(value || placeholder, Math.max(0, width - visualWidth(`${marker}[]`)));
  const raw = `${marker}[${rawValue}]`;
  const text = [
    selected ? accent(marker) : marker,
    '[',
    value ? (selected ? success(rawValue) : rawValue) : dim(rawValue),
    ']',
  ].join('');
  return line(text, raw, width);
}

function reasoningLine(efforts: string[], index: number, width: number): RenderedLine {
  const effort = efforts[clampIndex(index, efforts.length)] ?? '';
  const label = reasoningEffortLabel(effort);
  const raw = `  ◉ ${label} effort ←/→ to adjust`;
  return line(`${accent('  ◉')} ${label} effort ${dim('←/→ to adjust')}`, raw, width);
}

function modelLabel(model: UiModelInfo): string {
  const id = model.modelId?.trim();
  return id || model.name || 'Unknown model';
}

function customModelCursorX(
  panel: Extract<TerminalPresentation['commandPanel'], { kind: 'model' }>,
  width: number,
): number {
  return Math.min(visualWidth(`❯ [${panel.customModel.trim()}`), Math.max(0, width - 1));
}

function customProviderId(panel: Extract<TerminalPresentation['commandPanel'], { kind: 'model' }>): string | undefined {
  const currentProvider = panel.catalog.current?.providerId?.trim();
  if (currentProvider) {
    return currentProvider;
  }
  return panel.catalog.models?.find(model => model.providerId?.trim())?.providerId?.trim() || undefined;
}

function isCurrentModel(model: UiModelInfo, current: UiModelSelection | null | undefined): boolean {
  return Boolean(
    model.current
    || (model.providerId === current?.providerId && model.modelId === current?.modelId),
  );
}

function modelLabelWidth(models: UiModelInfo[], width: number): number {
  const longest = Math.max(12, ...models.map(model => visualWidth(`${modelLabel(model)} ✔`)));
  return Math.min(longest, Math.max(12, width - 8));
}

function reasoningEffortLabel(effort: string): string {
  switch (effort.toUpperCase()) {
    case 'XHIGH':
      return 'xHigh';
    case 'HIGH':
      return 'High';
    case 'MEDIUM':
      return 'Medium';
    case 'LOW':
      return 'Low';
    case 'MINIMAL':
      return 'Minimal';
    case 'NONE':
      return 'None';
    default:
      return effort || 'Default';
  }
}
