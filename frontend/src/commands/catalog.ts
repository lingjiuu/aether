export type SlashCommandInfo = {
  usage: string;
  description: string;
  placeholder?: string;
  aliases?: string[];
};

const slashCommands: SlashCommandInfo[] = [
  { usage: '/new', description: 'Start a new session in the current working directory' },
  { usage: '/resume', description: 'Browse previous sessions; add an id to resume directly' },
  { usage: '/compact', description: 'Compact the current conversation context' },
  { usage: '/model', description: 'Choose what model and reasoning effort to use' },
  { usage: '/permissions', description: 'Choose the tool permission mode for this session' },
  { usage: '/skills', description: 'List loaded skills' },
  { usage: '/rename', description: 'Rename the current session', placeholder: 'name', aliases: ['/name'] },
  { usage: '/help', description: 'Open command help' },
  { usage: '/quit', description: 'Exit Aether' },
];

export function getSlashCommandSuggestions(input: string, limit = slashCommands.length): SlashCommandInfo[] {
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
    .filter(command => commandMatchesFragment(command, fragment))
    .slice(0, limit);
  const onlyMatch = matches[0];
  if (matches.length === 1 && onlyMatch && commandMatchesName(onlyMatch, fragment) && !expectsArguments(onlyMatch)) {
    return [];
  }
  return matches;
}

export function getSlashCommandInsertText(command: SlashCommandInfo): string {
  const name = commandName(command);
  return command.placeholder ? `/${name} ` : `/${name}`;
}

function commandName(command: SlashCommandInfo): string {
  return (command.usage.split(/\s+/)[0] ?? command.usage).replace(/^\//, '');
}

function expectsArguments(command: SlashCommandInfo): boolean {
  return Boolean(command.placeholder);
}

export function getSlashCommandPlaceholder(input: string): string | undefined {
  const trimmed = input.trimStart();
  if (!trimmed.startsWith('/')) {
    return undefined;
  }

  const body = trimmed.slice(1);
  if (!body || !/\s/.test(body)) {
    return undefined;
  }

  const name = body.split(/\s+/)[0]?.toLowerCase();
  if (!name) {
    return undefined;
  }

  const command = slashCommands.find(entry => commandMatchesName(entry, name));
  if (!command?.placeholder) {
    return undefined;
  }

  return body.trimEnd() === name ? command.placeholder : undefined;
}

function commandMatchesName(command: SlashCommandInfo, name: string): boolean {
  return commandNames(command).includes(name);
}

function commandMatchesFragment(command: SlashCommandInfo, fragment: string): boolean {
  return commandNames(command).some(name => name.startsWith(fragment));
}

function commandNames(command: SlashCommandInfo): string[] {
  return [commandName(command), ...(command.aliases ?? [])].map(name => name.replace(/^\//, '').toLowerCase());
}
