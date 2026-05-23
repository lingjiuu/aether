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

  const body = trimmed.slice(1);
  if (/\s/.test(body)) {
    return [];
  }

  const fragment = body.toLowerCase();
  if (!fragment) {
    return slashCommands.slice(0, limit);
  }

  const matches = slashCommands
    .filter(command => command.usage.slice(1).toLowerCase().startsWith(fragment))
    .slice(0, limit);
  const onlyMatch = matches[0];
  if (matches.length === 1 && onlyMatch && commandName(onlyMatch) === fragment && !expectsArguments(onlyMatch)) {
    return [];
  }
  return matches;
}

export function getSlashCommandInsertText(command: SlashCommandInfo): string {
  const name = commandName(command);
  return expectsArguments(command) ? `/${name} ` : `/${name}`;
}

function commandName(command: SlashCommandInfo): string {
  return (command.usage.split(/\s+/)[0] ?? command.usage).replace(/^\//, '');
}

function expectsArguments(command: SlashCommandInfo): boolean {
  return command.usage.includes(' ');
}
