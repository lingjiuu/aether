import { getSlashCommandPlaceholder, getSlashCommandSuggestions } from '../../../commands/slashCommands.js';
import type { ComposerFileSuggestion, ComposerPopup } from '../../../state/reducer.js';
import { accent, dim } from '../../shared/ansi.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { block, line, PROMPT, separator } from '../renderPrimitives.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { padPlain, visualWidth } from '../../shared/text.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

export function renderComposer(
  presentation: TerminalPresentation,
  width: number,
  composerCursorOffset: number,
): RenderBlock {
  const value = presentation.composer.value;
  const suggestions = presentation.composer.commandPaletteOpen ? getSlashCommandSuggestions(value) : [];
  const popup = presentation.composer.popup;
  const selectedIndex = clampIndex(
    presentation.composer.selectedSuggestionIndex,
    popup ? popup.items.length : suggestions.length,
  );
  const usageWidth = Math.max(0, ...suggestions.map(command => visualWidth(command.usage)));
  const lines: RenderedLine[] = [];

  lines.push(separator(width));
  const cursorOffset = clampIndex(composerCursorOffset, value.length + 1);
  const beforeCursor = value.slice(0, cursorOffset);
  const afterCursor = value.slice(cursorOffset);
  const placeholder = cursorOffset === value.length ? getSlashCommandPlaceholder(value) : undefined;
  const rawPromptLine = `${PROMPT}${value}${placeholder ? `[${placeholder}]` : ''}`;
  const renderedPromptLine = `${accent(PROMPT)}${beforeCursor}${placeholder ? dim(`[${placeholder}]`) : ''}${afterCursor}`;
  lines.push(line(renderedPromptLine, rawPromptLine, width));
  lines.push(separator(width));
  if (popup) {
    lines.push(...renderPopup(popup, selectedIndex, width));
  } else if (suggestions.length) {
    for (const [index, command] of suggestions.entries()) {
      const marker = index === selectedIndex ? '› ' : '  ';
      const usage = `${marker}${padPlain(command.usage, usageWidth)}`;
      const raw = `${usage} ${command.description}`;
      const styledUsage = index === selectedIndex ? accent(usage) : dim(usage);
      lines.push(line(`${styledUsage} ${dim(command.description)}`, raw, width));
    }
  }
  const cursorX = visualWidth(`${PROMPT}${beforeCursor}`);
  return block('composer', lines, { x: Math.min(cursorX, width - 1), y: 1 });
}

function renderPopup(popup: ComposerPopup, selectedIndex: number, width: number): RenderedLine[] {
  if (popup.kind === 'skill') {
    const nameWidth = Math.max(0, ...popup.items.map(skill => visualWidth(`$${skill.name ?? ''}`)));
    return popup.items.map((skill, index) => {
      const marker = index === selectedIndex ? '› ' : '  ';
      const name = padPlain(`$${skill.name ?? 'unknown'}`, nameWidth);
      const description = skill.description ?? skill.location ?? skill.path ?? '';
      const raw = `${marker}${name} ${description}`.trimEnd();
      const styledName = index === selectedIndex ? accent(`${marker}${name}`) : dim(`${marker}${name}`);
      return line(`${styledName} ${dim(description)}`, raw, width);
    });
  }

  const pathWidth = Math.max(0, ...popup.items.map(file => visualWidth(`@${file.displayPath}`)));
  return popup.items.map((file, index) => renderFileSuggestion(file, index, selectedIndex, pathWidth, width));
}

function renderFileSuggestion(
  file: ComposerFileSuggestion,
  index: number,
  selectedIndex: number,
  pathWidth: number,
  width: number,
): RenderedLine {
  const marker = index === selectedIndex ? '› ' : '  ';
  const displayPath = padPlain(`@${file.displayPath}`, pathWidth);
  const kind = file.isImage ? 'image' : 'file';
  const raw = `${marker}${displayPath} ${kind}`.trimEnd();
  const styledPath = index === selectedIndex ? accent(`${marker}${displayPath}`) : dim(`${marker}${displayPath}`);
  return line(`${styledPath} ${dim(kind)}`, raw, width);
}
