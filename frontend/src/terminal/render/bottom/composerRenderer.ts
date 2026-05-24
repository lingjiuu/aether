import { getSlashCommandPlaceholder, getSlashCommandSuggestions } from '../../../commands/slashCommands.js';
import { accent, dim } from '../../shared/ansi.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { line, PROMPT, separator } from '../renderPrimitives.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { visualWidth } from '../../shared/text.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderComposer(
  presentation: TerminalPresentation,
  width: number,
  composerCursorOffset: number,
): RenderBlock {
  const value = presentation.composer.value;
  const suggestions = presentation.composer.commandPaletteOpen ? getSlashCommandSuggestions(value) : [];
  const selectedIndex = clampIndex(presentation.composer.selectedSuggestionIndex, suggestions.length);
  const usageWidth = Math.max(0, ...suggestions.map(command => command.usage.length));
  const lines: RenderedLine[] = [];

  if (suggestions.length) {
    for (const [index, command] of suggestions.entries()) {
      const marker = index === selectedIndex ? '› ' : '  ';
      const usage = `${marker}${command.usage.padEnd(usageWidth)}`;
      const raw = `${usage} ${command.description}`;
      const styledUsage = index === selectedIndex ? accent(usage) : dim(usage);
      lines.push(line(`${styledUsage} ${dim(command.description)}`, raw, width));
    }
  }

  lines.push(separator(width));
  const cursorOffset = clampIndex(composerCursorOffset, value.length + 1);
  const beforeCursor = value.slice(0, cursorOffset);
  const afterCursor = value.slice(cursorOffset);
  const placeholder = cursorOffset === value.length ? getSlashCommandPlaceholder(value) : undefined;
  const rawPromptLine = `${PROMPT}${value}${placeholder ? `[${placeholder}]` : ''}`;
  const renderedPromptLine = `${accent(PROMPT)}${beforeCursor}${placeholder ? dim(`[${placeholder}]`) : ''}${afterCursor}`;
  lines.push(line(renderedPromptLine, rawPromptLine, width));
  lines.push(separator(width));
  const cursorX = visualWidth(`${PROMPT}${beforeCursor}`);
  return { lines, cursor: { x: Math.min(cursorX, width - 1), y: lines.length - 2 } };
}
