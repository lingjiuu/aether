import type { ToolPresentationDefinition, ToolPresenter } from './types.js';
import { fileToolPresentations } from './filePresenters.js';
import { searchToolPresentations } from './searchPresenters.js';
import { shellToolPresentations } from './shellPresenter.js';

const toolPresentations: ToolPresentationDefinition[] = [
  ...fileToolPresentations,
  ...searchToolPresentations,
  ...shellToolPresentations,
];

const presenters = new Map<string, ToolPresenter>(
  toolPresentations.flatMap(definition => definition.names.map(name => [name, definition.presenter] as const)),
);

export function presenterFor(toolName: string): ToolPresenter | undefined {
  return presenters.get(toolName);
}
