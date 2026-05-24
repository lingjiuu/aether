import type { ToolPresenter } from './types.js';
import { editPresenter, readPresenter, writePresenter } from './filePresenters.js';
import { findPresenter, grepPresenter, lsPresenter } from './searchPresenters.js';
import { bashPresenter } from './shellPresenter.js';

const presenters = new Map<string, ToolPresenter>([
  ['read', readPresenter],
  ['write', writePresenter],
  ['edit', editPresenter],
  ['ls', lsPresenter],
  ['grep', grepPresenter],
  ['find', findPresenter],
  ['bash', bashPresenter],
]);

export function presenterFor(toolName: string): ToolPresenter | undefined {
  return presenters.get(toolName);
}
