import type { SkillInfo } from '../protocol/wire.js';

export function filterSkills<T extends SkillInfo>(skills: T[], query: string): T[] {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return skills;
  }

  return skills.filter(skill =>
    [skill.name, skill.description, skill.location, skill.path, skillScope(skill)]
      .filter(Boolean)
      .some(value => value?.toLowerCase().includes(normalizedQuery)),
  );
}

export function skillScope(skill: SkillInfo, cwd?: string | null): string {
  const location = skill.location ?? skill.path ?? '';
  const normalizedCwd = cwd?.replace(/\/+$/, '');
  if (
    normalizedCwd
    && (
      location.startsWith(`${normalizedCwd}/.aether/skills/`)
      || location.startsWith(`${normalizedCwd}/.agent/skills/`)
    )
  ) {
    return 'project';
  }
  return 'user';
}

export function formatSkillCount(count: number): string {
  return `${count} ${count === 1 ? 'skill' : 'skills'}`;
}
