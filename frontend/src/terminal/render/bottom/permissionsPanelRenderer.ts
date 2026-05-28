import type { UiPermissionMode } from '../../../protocol/wire.js';
import { accent, bold, dim, success } from '../../shared/ansi.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { padPlain, truncatePlain, visualWidth, wrapPlain } from '../../shared/text.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, block, line } from '../renderPrimitives.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderPermissionsPanel(presentation: TerminalPresentation, width: number): RenderBlock {
  const panel = presentation.commandPanel;
  if (panel?.kind !== 'permissions') {
    return block('permissions-panel');
  }

  const modes = panel.catalog.modes ?? [];
  const selectedIndex = clampIndex(panel.selectedIndex, modes.length);
  const lines: RenderedLine[] = [
    line(`  ${accent(bold('Permissions'))}`, '  Permissions', width),
    ...descriptionLines(width),
    blankLine(),
  ];

  if (modes.length) {
    const labelWidth = modeLabelWidth(modes, width);
    for (const [index, mode] of modes.entries()) {
      lines.push(modeLine(mode, panel.catalog.current, index, index === selectedIndex, labelWidth, width));
    }
  } else {
    lines.push(line(`  ${dim('No permission modes available')}`, '  No permission modes available', width));
  }

  lines.push(blankLine());
  lines.push(line(`  ${dim('Enter to confirm · Esc to cancel')}`, '  Enter to confirm · Esc to cancel', width));
  return block('permissions-panel', lines);
}

function descriptionLines(width: number): RenderedLine[] {
  const description = 'Choose how much autonomy Aether tools have in this session.';
  return wrapPlain(description, Math.max(10, width - 2))
    .map(part => line(`  ${dim(part)}`, `  ${part}`, width));
}

function modeLine(
  mode: UiPermissionMode,
  current: UiPermissionMode | null | undefined,
  index: number,
  selected: boolean,
  labelWidth: number,
  width: number,
): RenderedLine {
  const marker = selected ? '❯ ' : '  ';
  const ordinal = `${index + 1}. `;
  const check = isCurrentMode(mode, current) ? ' ✓' : '';
  const rawLabel = truncatePlain(`${modeLabel(mode)}${check}`, labelWidth);
  const labelCell = padPlain(rawLabel, labelWidth);
  const description = mode.description?.trim() || 'Custom permission mode';
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

function modeLabel(mode: UiPermissionMode): string {
  return mode.name?.trim() || mode.id?.trim() || 'Unknown mode';
}

function isCurrentMode(mode: UiPermissionMode, current: UiPermissionMode | null | undefined): boolean {
  return Boolean(mode.current || (mode.id && mode.id === current?.id));
}

function modeLabelWidth(modes: UiPermissionMode[], width: number): number {
  const longest = Math.max(12, ...modes.map(mode => visualWidth(`${modeLabel(mode)} ✓`)));
  return Math.min(longest, Math.max(12, width - 66));
}
