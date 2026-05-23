export type SlashCommandInfo = {
  usage: string;
};

const slashCommands: SlashCommandInfo[] = [
  { usage: '/new' },
  { usage: '/resume <session-id>' },
  { usage: '/sessions' },
  { usage: '/compact' },
  { usage: '/continue' },
  { usage: '/cancel' },
  { usage: '/skills' },
  { usage: '/reload-skills' },
  { usage: '/name <session-name>' },
  { usage: '/approve' },
  { usage: '/deny' },
  { usage: '/quit' },
  { usage: '/help' },
];

export function getSlashCommandSuggestions(input: string, limit = 6): SlashCommandInfo[] {
  const trimmed = input.trimStart();
  if (!trimmed.startsWith('/')) {
    return [];
  }

  const fragment = trimmed.slice(1).split(/\s+/)[0]?.toLowerCase() ?? '';
  if (!fragment) {
    return slashCommands.slice(0, limit);
  }

  return slashCommands
    .filter(command => command.usage.slice(1).toLowerCase().startsWith(fragment))
    .slice(0, limit);
}
