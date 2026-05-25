import type { UiModelInfo, UiModelSelection } from '../../../protocol/wire.js';
import { accent, bold, dim, success } from '../../shared/ansi.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { padPlain, truncatePlain, visualWidth, wrapPlain } from '../../shared/text.js';
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
  const selectedIndex = clampIndex(panel.selectedIndex, models.length);
  const lines: RenderedLine[] = [
    line(`  ${accent(bold('Select model'))}`, '  Select model', width),
    ...descriptionLines(width),
    blankLine(),
  ];

  if (models.length) {
    const fixedRows = 9;
    const visibleCount = Math.max(1, Math.min(MODEL_VISIBLE_COUNT, maxRows - fixedRows, models.length));
    const visibleStart = Math.max(
      0,
      Math.min(Math.max(selectedIndex - Math.floor(visibleCount / 2), 0), Math.max(models.length - visibleCount, 0)),
    );
    const visibleModels = models.slice(visibleStart, visibleStart + visibleCount);
    const labelWidth = modelLabelWidth(visibleModels, width);
    for (const [offset, model] of visibleModels.entries()) {
      const index = visibleStart + offset;
      lines.push(modelLine(model, panel.catalog.current, index, index === selectedIndex, labelWidth, width));
    }
  } else {
    lines.push(line(`  ${dim('No models available')}`, '  No models available', width));
  }

  lines.push(blankLine());
  if ((panel.catalog.reasoningEfforts ?? []).length) {
    lines.push(reasoningLine(panel.catalog.reasoningEfforts ?? [], panel.reasoningIndex, width));
    lines.push(blankLine());
  }
  lines.push(line(`  ${dim('Enter to confirm · Esc to cancel')}`, '  Enter to confirm · Esc to cancel', width));
  return block('model-panel', lines);
}

function descriptionLines(width: number): RenderedLine[] {
  const description = 'Switch between Aether models. Applies to this session and subsequent turns.';
  return wrapPlain(description, Math.max(10, width - 2))
    .map(part => line(`  ${dim(part)}`, `  ${part}`, width));
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
  const description = modelDescription(model);
  const raw = `${marker}${ordinal}${labelCell}  ${description}`;
  const text = [
    selected ? accent(marker) : marker,
    ordinal,
    selected ? success(labelCell) : labelCell,
    '  ',
    dim(description),
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
  const provider = model.providerId?.trim();
  const id = model.modelId?.trim();
  return [provider, id].filter(Boolean).join('/') || id || model.name || 'Unknown model';
}

function modelDescription(model: UiModelInfo): string {
  const name = model.name?.trim();
  const id = model.modelId?.trim();
  return name && name !== id ? name : 'Custom model';
}

function isCurrentModel(model: UiModelInfo, current: UiModelSelection | null | undefined): boolean {
  return Boolean(
    model.current
    || (model.providerId === current?.providerId && model.modelId === current?.modelId),
  );
}

function modelLabelWidth(models: UiModelInfo[], width: number): number {
  const longest = Math.max(12, ...models.map(model => visualWidth(`${modelLabel(model)} ✔`)));
  return Math.min(longest, Math.max(12, width - 34));
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
