import { filterSkills, formatSkillCount, skillScope } from '../../../domain/skills.js';
import type { SkillInfo } from '../../../protocol/wire.js';
import { accent, bold, dim, success } from '../../shared/ansi.js';
import { clampIndex } from '../../shared/terminalMath.js';
import { padPlain, truncatePlain, visualWidth } from '../../shared/text.js';
import type { TerminalPresentation } from '../presentationModel.js';
import { blankLine, block, line } from '../renderPrimitives.js';
import type { RenderBlock, RenderedLine } from '../viewModel.js';

const SKILLS_VISIBLE_COUNT = 8;

export function renderSkillsPanel(presentation: TerminalPresentation, width: number, maxRows: number): RenderBlock {
  const panel = presentation.commandPanel;
  if (panel?.kind !== 'skills') {
    return block('skills-panel');
  }

  const skills = filterSkills(panel.skills, panel.query);
  const selectedIndex = clampIndex(panel.selectedIndex, skills.length);
  const lines: RenderedLine[] = [
    line(`  ${accent(bold('Skills'))}`, '  Skills', width),
    line(`  ${dim(`${formatSkillCount(panel.skills.length)} · / to search · Enter to close · Esc to cancel`)}`, `  ${formatSkillCount(panel.skills.length)} · / to search · Enter to close · Esc to cancel`, width),
    blankLine(),
    ...searchBoxLines(panel.query, width),
    blankLine(),
  ];

  if (skills.length) {
    const fixedRows = 8;
    const visibleCount = Math.max(1, Math.min(SKILLS_VISIBLE_COUNT, maxRows - fixedRows, skills.length));
    const visibleStart = Math.max(
      0,
      Math.min(Math.max(selectedIndex - Math.floor(visibleCount / 2), 0), Math.max(skills.length - visibleCount, 0)),
    );
    const visibleSkills = skills.slice(visibleStart, visibleStart + visibleCount);
    const nameWidth = skillNameWidth(visibleSkills, width);
    for (const [offset, skill] of visibleSkills.entries()) {
      const index = visibleStart + offset;
      lines.push(skillLine(skill, index === selectedIndex, nameWidth, width, presentation.session.cwd));
    }
  } else {
    const emptyText = panel.query ? 'No matching skills' : 'No skills found';
    lines.push(line(`  ${dim(emptyText)}`, `  ${emptyText}`, width));
  }

  return block('skills-panel', lines, { x: Math.min(4 + visualWidth(panel.query), width - 3), y: 4 });
}

function searchBoxLines(query: string, width: number): RenderedLine[] {
  const searchText = query || '⌕ Search skills…';
  const innerWidth = Math.max(0, width - 6);
  const borderWidth = Math.max(0, width - 4);
  const padding = ' '.repeat(Math.max(0, innerWidth - visualWidth(searchText)));
  return [
    line(`  ╭${'─'.repeat(borderWidth)}╮`, `  ╭${'─'.repeat(borderWidth)}╮`, width),
    line(`  │ ${query ? query : dim(searchText)}${padding} │`, `  │ ${searchText}${padding} │`, width),
    line(`  ╰${'─'.repeat(borderWidth)}╯`, `  ╰${'─'.repeat(borderWidth)}╯`, width),
  ];
}

function skillLine(skill: SkillInfo, selected: boolean, nameWidth: number, width: number, cwd?: string | null): RenderedLine {
  const marker = selected ? '❯ ' : '  ';
  const status = '✓ on';
  const name = truncatePlain(skill.name?.trim() || 'Unnamed skill', nameWidth);
  const nameCell = padPlain(name, nameWidth);
  const scope = skillScope(skill, cwd);
  const raw = `${marker}${status}  ${nameCell} · ${scope}`;
  const text = [
    selected ? accent(marker) : marker,
    success(status),
    '  ',
    accent(nameCell),
    dim(` · ${scope}`),
  ].join('');
  return line(text, raw, width);
}

function skillNameWidth(skills: SkillInfo[], width: number): number {
  const longest = Math.max(12, ...skills.map(skill => visualWidth(skill.name?.trim() || 'Unnamed skill')));
  return Math.min(longest, Math.max(12, width - 22));
}
