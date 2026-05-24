import { getSlashCommandSuggestions } from '../../../commands/slashCommands.js';
import { bold, dim } from '../../shared/ansi.js';
import { blankLine, line } from '../renderPrimitives.js';
import type { RenderedLine } from '../viewModel.js';

export function renderHelpPanel(width: number): RenderedLine[] {
  const commands = getSlashCommandSuggestions('/');
  const usageWidth = Math.max(0, ...commands.map(command => command.usage.length));
  const lines: RenderedLine[] = [
    line(`${bold('Help')}  ${dim('General')}  ${dim('Commands')}  ${dim('Custom commands')}`, 'Help  General  Commands  Custom commands', width),
    blankLine(),
    line(dim('Browse default commands'), 'Browse default commands', width),
    blankLine(),
  ];
  for (const command of commands) {
    const raw = `  ${command.usage.padEnd(usageWidth)}  ${command.description}`;
    lines.push(line(`  ${command.usage.padEnd(usageWidth)}  ${dim(command.description)}`, raw, width));
  }
  lines.push(blankLine());
  lines.push(line(dim('Esc to cancel'), 'Esc to cancel', width));
  return lines;
}
