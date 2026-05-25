export type SlashCommand =
  | { kind: 'submit'; text: string }
  | { kind: 'new' }
  | { kind: 'resume'; sessionId?: string }
  | { kind: 'sessions' }
  | { kind: 'compact' }
  | { kind: 'cancel' }
  | { kind: 'skills' }
  | { kind: 'reloadSkills' }
  | { kind: 'model' }
  | { kind: 'name'; name: string }
  | { kind: 'approve' }
  | { kind: 'deny' }
  | { kind: 'quit' }
  | { kind: 'help' }
  | { kind: 'unknown'; name: string };

export function parseCommand(input: string): SlashCommand {
  const trimmed = input.trim();
  if (!trimmed.startsWith('/')) {
    return { kind: 'submit', text: input };
  }

  const [rawName = '', ...rest] = trimmed.slice(1).split(/\s+/);
  const name = rawName.toLowerCase();
  const arg = rest.join(' ').trim();

  switch (name) {
    case '':
      return { kind: 'submit', text: input };
    case 'new':
      return { kind: 'new' };
    case 'resume':
      return arg ? { kind: 'resume', sessionId: arg } : { kind: 'resume' };
    case 'sessions':
      return { kind: 'sessions' };
    case 'compact':
      return { kind: 'compact' };
    case 'cancel':
      return { kind: 'cancel' };
    case 'skills':
      return { kind: 'skills' };
    case 'reload-skills':
    case 'skills-reload':
      return { kind: 'reloadSkills' };
    case 'model':
      return { kind: 'model' };
    case 'name':
    case 'rename':
      return { kind: 'name', name: arg };
    case 'approve':
    case 'yes':
    case 'y':
      return { kind: 'approve' };
    case 'deny':
    case 'no':
    case 'n':
      return { kind: 'deny' };
    case 'q':
    case 'quit':
    case 'exit':
      return { kind: 'quit' };
    case 'help':
      return { kind: 'help' };
    default:
      return { kind: 'unknown', name };
  }
}
