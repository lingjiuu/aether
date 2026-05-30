import type { TimelineItem } from '../../domain/timeline.js';
import { defaultToolErrorView, defaultToolResultView } from './tools/resultPresenter.js';
import { presenterFor } from './tools/registry.js';
import type { ToolResultView, ToolUseView } from './tools/types.js';
import {
  clean,
  defaultToolUseSummary,
  defaultUserFacingName,
  displayDetails,
  detailsKind,
  detailsRecord,
  toolArguments,
} from './tools/utils.js';

export type {
  ToolLine,
  ToolLineTone,
  ToolResultView,
  ToolUseView,
} from './tools/types.js';

export function toolUseView(item: TimelineItem, cwd?: string | null): ToolUseView {
  const toolCall = item.toolCall;
  const toolName = normalizedToolName(item);
  const args = toolArguments(toolCall)
    ?? detailsRecord(item.toolResult?.details)
    ?? detailsRecord(item.toolUpdate?.details);
  const presenter = presenterFor(toolName);
  const name = presenter?.userFacingName?.(toolName, toolCall)
    ?? defaultUserFacingName(toolName, toolCall);
  const summary = presenter?.useSummary?.(args, toolCall, toolName, cwd)
    ?? defaultToolUseSummary(args, toolCall, toolName);
  return summary ? { name, summary } : { name };
}

export function toolProgressView(item: TimelineItem): ToolResultView {
  const update = item.toolUpdate;
  const details = displayDetails(update) ?? {};
  const kind = detailsKind(details, update, item.toolCall);
  const view = presenterFor(kind)?.progressView?.(details, update);
  if (view) {
    return view;
  }

  const updateText = clean(update?.text);
  return { lines: [{ text: updateText || statusProgressText(update?.status), tone: 'dim' }] };
}

export function toolResultView(item: TimelineItem, cwd?: string | null): ToolResultView {
  const result = item.toolResult;
  if (!result) {
    return { lines: [] };
  }

  const details = displayDetails(result) ?? {};
  const kind = detailsKind(details, result, item.toolCall);
  const presenter = presenterFor(kind);
  if (result.error) {
    return presenter?.errorView?.(details, result, cwd)
      ?? defaultToolErrorView(result);
  }
  return presenter?.resultView?.(details, result, cwd)
    ?? defaultToolResultView(result);
}

function normalizedToolName(item: TimelineItem): string {
  return clean(item.toolCall?.toolName)
    ?? clean(item.toolResult?.toolName)
    ?? clean(item.toolUpdate?.toolName)
    ?? clean(item.toolCall?.displayName)
    ?? 'tool';
}

function statusProgressText(status?: string | null): string {
  return status === 'WAITING_APPROVAL' ? 'Waiting for approval...' : 'Running...';
}
