import type { UiToolCall, UiToolResult, UiToolUpdate } from '../../../protocol/wire.js';

export type ToolLineTone = 'normal' | 'dim' | 'error' | 'success';

export type ToolLine = {
  text: string;
  tone?: ToolLineTone;
};

export type ToolUseView = {
  name: string;
  summary?: string;
};

export type ToolResultView = {
  lines: ToolLine[];
};

export type Details = Record<string, unknown>;

export type ToolPresenter = {
  userFacingName?: (toolName: string, toolCall: UiToolCall | undefined) => string;
  useSummary?: (args: Details | undefined, toolCall: UiToolCall | undefined, toolName: string, cwd?: string | null) => string | undefined;
  progressView?: (details: Details, update: UiToolUpdate | undefined) => ToolResultView | undefined;
  errorView?: (details: Details, result: UiToolResult, cwd?: string | null) => ToolResultView;
  resultView?: (details: Details, result: UiToolResult, cwd?: string | null) => ToolResultView;
};

export type ToolPresentationDefinition = {
  names: readonly string[];
  presenter: ToolPresenter;
};
