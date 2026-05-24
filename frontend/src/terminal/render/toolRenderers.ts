import type { TimelineItem } from '../../domain/timeline.js';
import { fallbackResultView } from './tools/fallbackPresenter.js';
import { presenterFor } from './tools/registry.js';
import type { ToolResultView, ToolUseView } from './tools/types.js';
import {
  clean,
  defaultToolUseSummary,
  defaultUserFacingName,
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

export function toolUseView(item: TimelineItem): ToolUseView {
  const toolCall = item.toolCall;
  const toolName = normalizedToolName(item);
  const args = toolArguments(toolCall)
    ?? detailsRecord(item.toolResult?.details)
    ?? detailsRecord(item.toolUpdate?.details);
  const presenter = presenterFor(toolName);
  const name = presenter?.userFacingName?.(toolName, toolCall)
    ?? defaultUserFacingName(toolName, toolCall);
  const summary = presenter?.useSummary?.(args, toolCall, toolName)
    ?? defaultToolUseSummary(args, toolCall, toolName);
  return summary ? { name, summary } : { name };
}

export function toolProgressView(item: TimelineItem): ToolResultView {
  const update = item.toolUpdate;
  const details = detailsRecord(update?.details) ?? {};
  const kind = detailsKind(details, update, item.toolCall);
  const view = presenterFor(kind)?.progressView?.(details, update);
  if (view) {
    return view;
  }

  const updateText = clean(update?.text);
  return { lines: [{ text: updateText || 'Running...', tone: 'dim' }] };
}

export function toolResultView(item: TimelineItem): ToolResultView {
  const result = item.toolResult;
  if (!result) {
    return { lines: [] };
  }

  const details = detailsRecord(result.details) ?? {};
  const kind = detailsKind(details, result, item.toolCall);
  return presenterFor(kind)?.resultView?.(details, result)
    ?? fallbackResultView(result);
}

function normalizedToolName(item: TimelineItem): string {
  return clean(item.toolCall?.toolName)
    ?? clean(item.toolResult?.toolName)
    ?? clean(item.toolUpdate?.toolName)
    ?? clean(item.toolCall?.displayName)
    ?? 'tool';
}
