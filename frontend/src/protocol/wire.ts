export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue };

export type UiEventType =
  | 'USER_MESSAGE'
  | 'SESSION_NAME_UPDATED'
  | 'TURN_STARTED'
  | 'ITEM_STARTED'
  | 'ITEM_COMPLETED'
  | 'ASSISTANT_TEXT_DELTA'
  | 'REASONING_DELTA'
  | 'TOOL_CALL_ARGUMENTS_DELTA'
  | 'TOOL_CALL_ARGUMENTS_DONE'
  | 'TOKEN_USAGE'
  | 'TOOL_CALL'
  | 'TOOL_EXECUTION_BEGIN'
  | 'TOOL_EXECUTION_UPDATE'
  | 'TOOL_EXECUTION_END'
  | 'TOOL_RESULT'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_RESOLVED'
  | 'CONTEXT_MESSAGE'
  | 'TURN_COMPLETED'
  | 'TURN_ABORTED'
  | 'COMPACT_STARTED'
  | 'COMPACT_FINISHED'
  | 'COMPACT_SKIPPED'
  | 'SESSION_RESET'
  | 'MODEL_CHANGED'
  | 'PERMISSION_CHANGED'
  | 'SKILLS_CHANGED'
  | 'STREAM_RETRY'
  | 'ERROR';

export type UiItemKind =
  | 'USER_MESSAGE'
  | 'ASSISTANT_TEXT'
  | 'REASONING'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'CONTEXT_MESSAGE';

export type UiToolCall = {
  itemId?: string | null;
  contentIndex?: number | null;
  toolCallId?: string | null;
  toolName?: string | null;
  argumentsJson?: string | null;
  arguments?: unknown;
  displayName?: string | null;
  displaySummary?: string | null;
  riskLevel?: string | null;
};

export type UiToolResult = {
  itemId?: string | null;
  sourceItemId?: string | null;
  contentIndex?: number | null;
  toolCallId?: string | null;
  toolName?: string | null;
  text?: string | null;
  error?: boolean;
  status?: string | null;
  durationMs?: number | null;
  approvalWaitMs?: number | null;
  executionDurationMs?: number | null;
  details?: unknown;
  display?: unknown;
  truncated?: boolean | null;
};

export type UiToolUpdate = {
  itemId?: string | null;
  sourceItemId?: string | null;
  contentIndex?: number | null;
  toolCallId?: string | null;
  toolName?: string | null;
  status?: string | null;
  text?: string | null;
  durationMs?: number | null;
  approvalWaitMs?: number | null;
  executionDurationMs?: number | null;
  details?: unknown;
  display?: unknown;
  truncated?: boolean | null;
};

export type UiItemBody =
  | { bodyType: 'text'; text?: string | null }
  | { bodyType: 'toolCall'; toolCall?: UiToolCall | null }
  | { bodyType: 'toolResult'; toolResult?: UiToolResult | null };

export type UiItem = {
  itemId?: string | null;
  kind?: UiItemKind | null;
  contentIndex?: number | null;
  body?: UiItemBody | null;
};

export type UiApprovalRequest = {
  approvalId?: string | null;
  toolCallId?: string | null;
  toolName?: string | null;
  riskLevel?: string | null;
  arguments?: Record<string, unknown> | null;
  reason?: string | null;
};

export type UiApprovalResponse = {
  approvalId?: string | null;
  approved?: boolean;
  reason?: string | null;
};

export type UiTokenCount = {
  inputTokens?: number | null;
  cachedInputTokens?: number | null;
  outputTokens?: number | null;
  reasoningOutputTokens?: number | null;
  totalTokens?: number | null;
};

export type UiTokenUsage = {
  total?: UiTokenCount | null;
  last?: UiTokenCount | null;
  modelContextWindow?: number | null;
  contextTokenUsage?: number | null;
  autoCompactTokenLimit?: number | null;
};

export type UiModelSelection = {
  providerId?: string | null;
  modelId?: string | null;
  name?: string | null;
  reasoningEffort?: string | null;
};

export type UiModelInfo = {
  providerId?: string | null;
  modelId?: string | null;
  name?: string | null;
  api?: string | null;
  contextWindowTokens?: number | null;
  autoCompactTokenLimit?: number | null;
  input?: string[] | null;
  current?: boolean;
};

export type UiModelCatalog = {
  current?: UiModelSelection | null;
  models?: UiModelInfo[] | null;
  reasoningEfforts?: string[] | null;
};

export type UiPermissionMode = {
  id?: string | null;
  name?: string | null;
  description?: string | null;
  current?: boolean;
};

export type UiPermissionCatalog = {
  current?: UiPermissionMode | null;
  modes?: UiPermissionMode[] | null;
};

export type UiEventPayload =
  | { payloadType: 'text'; text?: string | null }
  | { payloadType: 'sessionName'; sessionId?: string | null; name?: string | null }
  | { payloadType: 'userMessage'; item?: UiItem | null }
  | { payloadType: 'contextMessage'; item?: UiItem | null }
  | {
      payloadType: 'itemStarted';
      itemKind?: UiItemKind | null;
      itemId?: string | null;
      contentIndex?: number | null;
      toolCall?: UiToolCall | null;
    }
  | { payloadType: 'itemCompleted'; item?: UiItem | null }
  | {
      payloadType: 'textDelta';
      itemKind?: UiItemKind | null;
      itemId?: string | null;
      contentIndex?: number | null;
      delta?: string | null;
    }
  | {
      payloadType: 'toolArgumentsDelta';
      itemId?: string | null;
      contentIndex?: number | null;
      toolCall?: UiToolCall | null;
      delta?: string | null;
    }
  | { payloadType: 'toolArgumentsDone'; item?: UiItem | null }
  | { payloadType: 'toolCall'; toolCall?: UiToolCall | null }
  | {
      payloadType: 'toolExecution';
      toolCall?: UiToolCall | null;
      toolUpdate?: UiToolUpdate | null;
      toolResult?: UiToolResult | null;
    }
  | { payloadType: 'toolResult'; item?: UiItem | null }
  | { payloadType: 'approval'; request?: UiApprovalRequest | null; response?: UiApprovalResponse | null }
  | { payloadType: 'tokenUsage'; tokenUsage?: UiTokenUsage | null }
  | { payloadType: 'modelSelection'; modelSelection?: UiModelSelection | null }
  | { payloadType: 'permissionMode'; permissionMode?: UiPermissionMode | null }
  | {
      payloadType: 'compact';
      text?: string | null;
      originalMessageCount?: number | null;
      replacementMessageCount?: number | null;
    }
  | { payloadType: 'error'; message?: string | null };

export type UiEvent = {
  type?: UiEventType | null;
  sequence?: number | null;
  timestampMs?: number | null;
  sessionId?: string | null;
  commandId?: string | null;
  turnId?: string | null;
  turn?: number | null;
  payload?: UiEventPayload | null;
};

export type UiHistoryItem = {
  id?: string | null;
  kind?: UiItemKind | null;
  status?: string | null;
  contentIndex?: number | null;
  text?: string | null;
  toolCall?: UiToolCall | null;
  toolUpdate?: UiToolUpdate | null;
  toolResult?: UiToolResult | null;
};

export type UiTurn = {
  turnId?: string | null;
  commandId?: string | null;
  turn?: number | null;
  status?: string | null;
  items?: UiHistoryItem[] | null;
};

export type UiHistory = {
  sessionId?: string | null;
  turns?: UiTurn[] | null;
};

export type UiSessionSummary = {
  sessionId?: string | null;
  name?: string | null;
  preview?: string | null;
  createdAt?: number | null;
  updatedAt?: number | null;
  cwd?: string | null;
  modelProvider?: string | null;
  modelId?: string | null;
  recordCount?: number | null;
};

export type UiSessionState = {
  sessionId?: string | null;
  status?: string | null;
  messageCount?: number | null;
  availableSkillCount?: number | null;
  canContinue?: boolean;
  activeToolNames?: string[] | null;
  summary?: UiSessionSummary | null;
  reasoningEffort?: string | null;
  permissionMode?: string | null;
  tokenUsage?: UiTokenUsage | null;
};

export type UiEventPage = {
  sessionId?: string | null;
  afterSequence?: number | null;
  events?: UiEvent[] | null;
  nextAfterSequence?: number | null;
  hasMore?: boolean;
  replayRequired?: boolean;
};

export type UiCommandAck = {
  accepted?: boolean;
  commandId?: string | null;
  sessionId?: string | null;
  history?: UiHistory | null;
  message?: string | null;
};

export type TurnInputItem =
  | { type: 'text'; text: string }
  | { type: 'localImage'; path: string }
  | { type: 'skill'; name?: string | null; path?: string | null };

export type InitializeResult = {
  protocolVersion?: string;
  sessionId?: string;
  history?: UiHistory;
  session?: UiSessionState;
  capabilities?: Record<string, boolean>;
};

export type SkillInfo = {
  name?: string;
  description?: string;
  location?: string;
  path?: string;
  disableModelInvocation?: boolean;
};
