package io.github.lingjiuu.event;

import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.TokenUsage;
import io.github.lingjiuu.model.TokenUsageInfo;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.protocol.UiApprovalRequest;
import io.github.lingjiuu.protocol.UiApprovalResponse;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiItem;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.protocol.UiModelSelection;
import io.github.lingjiuu.protocol.UiPermissionMode;
import io.github.lingjiuu.protocol.UiTokenCount;
import io.github.lingjiuu.protocol.UiTokenUsage;
import io.github.lingjiuu.protocol.UiToolCall;
import io.github.lingjiuu.protocol.UiToolResult;
import io.github.lingjiuu.protocol.UiToolUpdate;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.tool.permission.PermissionPreset;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UiEvents {

    public static UiEvent turnStarted(TurnContext turnContext) {
        return event(UiEventType.TURN_STARTED, turnContext).build();
    }

    public static UiEvent turnAborted(TurnContext turnContext) {
        return event(UiEventType.TURN_ABORTED, turnContext).build();
    }

    public static UiEvent turnCompleted(TurnContext turnContext) {
        return event(UiEventType.TURN_COMPLETED, turnContext).build();
    }

    public static UiEvent sessionReset(String sessionId) {
        return event(UiEventType.SESSION_RESET, sessionId, null)
                .payload(new UiEventPayloads.Text("session reset"))
                .build();
    }

    public static UiEvent sessionNameUpdated(String sessionId, String name) {
        return event(UiEventType.SESSION_NAME_UPDATED, sessionId, null)
                .payload(new UiEventPayloads.SessionName(sessionId, name))
                .build();
    }

    public static UiEvent skillsChanged(String sessionId, int availableSkillCount) {
        return event(UiEventType.SKILLS_CHANGED, sessionId, null)
                .payload(new UiEventPayloads.Text("skills changed: " + availableSkillCount))
                .build();
    }

    public static UiEvent streamRetry(TurnContext turnContext, int attempt, int totalAttempts) {
        return event(UiEventType.STREAM_RETRY, turnContext)
                .payload(new UiEventPayloads.Text("Reconnecting... " + attempt + "/" + totalAttempts))
                .build();
    }

    public static UiEvent modelChanged(String sessionId, ModelSelection selection) {
        return event(UiEventType.MODEL_CHANGED, sessionId, null)
                .payload(new UiEventPayloads.ModelSelection(uiModelSelection(selection)))
                .build();
    }

    public static UiEvent permissionChanged(String sessionId, PermissionPreset preset) {
        return event(UiEventType.PERMISSION_CHANGED, sessionId, null)
                .payload(new UiEventPayloads.PermissionMode(uiPermissionMode(preset, preset)))
                .build();
    }

    public static UiEvent userMessage(UserMessage userMessage, TurnContext turnContext) {
        if (userMessage == null) {
            return null;
        }
        UiItem item = textItem(userMessage.id(), UiItemKind.USER_MESSAGE, null, MessageContents.text(userMessage));
        return event(UiEventType.USER_MESSAGE, turnContext)
                .payload(new UiEventPayloads.UserMessage(item))
                .build();
    }

    public static UiEvent contextMessage(ContextMessage contextMessage, TurnContext turnContext) {
        if (contextMessage == null) {
            return null;
        }
        UiItem item = textItem(contextMessage.id(), UiItemKind.CONTEXT_MESSAGE, null, MessageContents.text(contextMessage));
        return event(UiEventType.CONTEXT_MESSAGE, turnContext)
                .payload(new UiEventPayloads.ContextMessage(item))
                .build();
    }

    public static UiEvent itemStarted(
            TurnContext turnContext,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition
    ) {
        return event(UiEventType.ITEM_STARTED, turnContext)
                .payload(new UiEventPayloads.ItemStarted(
                        itemKind,
                        itemId,
                        contentIndex,
                        uiToolCall(itemId, contentIndex, toolCall, toolDefinition)
                ))
                .build();
    }

    public static UiEvent itemCompleted(
            TurnContext turnContext,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            String text
    ) {
        UiItem item = itemKind == UiItemKind.TOOL_CALL
                ? toolCallItem(itemId, contentIndex, uiToolCall(itemId, contentIndex, toolCall, toolDefinition))
                : textItem(itemId, itemKind, contentIndex, text);
        return event(UiEventType.ITEM_COMPLETED, turnContext)
                .payload(new UiEventPayloads.ItemCompleted(item))
                .build();
    }

    public static UiEvent assistantTextDelta(TurnContext turnContext, String itemId, Integer contentIndex, String delta) {
        return textDelta(turnContext, UiEventType.ASSISTANT_TEXT_DELTA, UiItemKind.ASSISTANT_TEXT, itemId, contentIndex, delta);
    }

    public static UiEvent reasoningDelta(TurnContext turnContext, String itemId, Integer contentIndex, String delta) {
        return textDelta(turnContext, UiEventType.REASONING_DELTA, UiItemKind.REASONING, itemId, contentIndex, delta);
    }

    public static UiEvent toolArgumentsDelta(
            TurnContext turnContext,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            String delta
    ) {
        return event(UiEventType.TOOL_CALL_ARGUMENTS_DELTA, turnContext)
                .payload(new UiEventPayloads.ToolArgumentsDelta(
                        itemId,
                        contentIndex,
                        uiToolCall(itemId, contentIndex, toolCall, toolDefinition),
                        delta
                ))
                .build();
    }

    public static UiEvent toolArgumentsDone(
            TurnContext turnContext,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition
    ) {
        return event(UiEventType.TOOL_CALL_ARGUMENTS_DONE, turnContext)
                .payload(new UiEventPayloads.ToolArgumentsDone(toolCallItem(
                        itemId,
                        contentIndex,
                        uiToolCall(itemId, contentIndex, toolCall, toolDefinition)
                )))
                .build();
    }

    public static UiEvent toolCall(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_CALL, turnContext)
                .payload(new UiEventPayloads.ToolCall(uiToolCall(itemId, contentIndex, toolCall, toolDefinition)))
                .build();
    }

    public static UiEvent toolExecutionBegin(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_EXECUTION_BEGIN, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(itemId, contentIndex, toolCall, toolDefinition),
                        uiToolUpdate(itemId, itemId, contentIndex, toolCall, null, "RUNNING", null),
                        null
                ))
                .build();
    }

    public static UiEvent toolExecutionUpdate(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            ToolDisplayResult partialResult,
            Long durationMs,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_EXECUTION_UPDATE, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(itemId, contentIndex, toolCall, toolDefinition),
                        uiToolUpdate(itemId, itemId, contentIndex, toolCall, partialResult, "RUNNING", durationMs),
                        null
                ))
                .build();
    }

    public static UiEvent toolExecutionWaitingApproval(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            Long durationMs,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_EXECUTION_UPDATE, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(itemId, contentIndex, toolCall, toolDefinition),
                        uiToolUpdate(itemId, itemId, contentIndex, toolCall, null, "WAITING_APPROVAL", durationMs, durationMs, null),
                        null
                ))
                .build();
    }

    public static UiEvent toolExecutionEnd(
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition,
            ToolResultMessage toolResult,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs,
            TurnContext turnContext
    ) {
        if (toolResult == null) {
            return null;
        }
        return event(UiEventType.TOOL_EXECUTION_END, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(sourceItemId, contentIndex, toolCall, toolDefinition),
                        uiToolUpdate(sourceItemId, sourceItemId, contentIndex, toolCall, null, status, durationMs, approvalWaitMs, executionDurationMs),
                        uiToolResult(toolResult, sourceItemId, contentIndex, status, durationMs, approvalWaitMs, executionDurationMs)
                ))
                .build();
    }

    public static UiEvent toolResult(
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs,
            TurnContext turnContext
    ) {
        if (toolResult == null) {
            return null;
        }
        return event(UiEventType.TOOL_RESULT, turnContext)
                .payload(new UiEventPayloads.ToolResult(toolResultItem(
                        toolResult,
                        sourceItemId,
                        contentIndex,
                        status,
                        durationMs,
                        approvalWaitMs,
                        executionDurationMs
                )))
                .build();
    }

    public static UiEvent error(TurnContext turnContext, String message) {
        return event(UiEventType.ERROR, turnContext)
                .payload(new UiEventPayloads.Error(message))
                .build();
    }

    public static UiEvent error(String sessionId, int turn, String message) {
        return event(UiEventType.ERROR, sessionId, turn)
                .payload(new UiEventPayloads.Error(message))
                .build();
    }

    public static UiEvent approvalRequested(ApprovalRequest request, TurnContext turnContext) {
        return event(UiEventType.APPROVAL_REQUESTED, turnContext)
                .payload(new UiEventPayloads.Approval(uiApprovalRequest(request), null))
                .build();
    }

    public static UiEvent approvalResolved(ApprovalRequest request, ApprovalResponse response, TurnContext turnContext) {
        return event(UiEventType.APPROVAL_RESOLVED, turnContext)
                .payload(new UiEventPayloads.Approval(uiApprovalRequest(request), uiApprovalResponse(response)))
                .build();
    }

    public static UiEvent tokenUsage(
            TurnContext turnContext,
            TokenUsageInfo tokenUsageInfo,
            long contextTokenUsage,
            Long autoCompactTokenLimit
    ) {
        return event(UiEventType.TOKEN_USAGE, turnContext)
                .payload(new UiEventPayloads.TokenUsage(uiTokenUsage(
                        tokenUsageInfo,
                        contextTokenUsage,
                        autoCompactTokenLimit
                )))
                .build();
    }

    public static UiEvent compactStarted(TurnContext turnContext, String trigger, int originalMessageCount) {
        return compactEvent(
                UiEventType.COMPACT_STARTED,
                turnContext,
                trigger,
                originalMessageCount,
                null
        );
    }

    public static UiEvent compactSkipped(
            TurnContext turnContext,
            String text,
            int originalMessageCount,
            int replacementMessageCount
    ) {
        return compactEvent(
                UiEventType.COMPACT_SKIPPED,
                turnContext,
                text,
                originalMessageCount,
                replacementMessageCount
        );
    }

    public static UiEvent compactFinished(
            TurnContext turnContext,
            String summary,
            int originalMessageCount,
            int replacementMessageCount
    ) {
        return compactEvent(
                UiEventType.COMPACT_FINISHED,
                turnContext,
                summary,
                originalMessageCount,
                replacementMessageCount
        );
    }

    private static UiEvent textDelta(
            TurnContext turnContext,
            UiEventType eventType,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            String delta
    ) {
        return event(eventType, turnContext)
                .payload(new UiEventPayloads.TextDelta(itemKind, itemId, contentIndex, delta))
                .build();
    }

    private static UiEvent compactEvent(
            UiEventType type,
            TurnContext turnContext,
            String text,
            int originalMessageCount,
            Integer replacementMessageCount
    ) {
        return event(type, turnContext)
                .payload(new UiEventPayloads.Compact(text, originalMessageCount, replacementMessageCount))
                .build();
    }

    private static UiEvent.UiEventBuilder event(UiEventType type, TurnContext turnContext) {
        return event(type, turnContext.sessionId(), turnContext.turn())
                .commandId(turnContext.commandId())
                .turnId(turnContext.turnId() == null ? null : turnContext.turnId().value());
    }

    private static UiEvent.UiEventBuilder event(UiEventType type, String sessionId, Integer turn) {
        return UiEvent.builder()
                .type(type)
                .sessionId(sessionId)
                .turn(turn);
    }

    private static UiItem textItem(String itemId, UiItemKind kind, Integer contentIndex, String text) {
        return UiItem.builder()
                .itemId(itemId)
                .kind(kind)
                .contentIndex(contentIndex)
                .body(new UiItemBodies.Text(text))
                .build();
    }

    private static UiItem toolCallItem(String itemId, Integer contentIndex, UiToolCall toolCall) {
        return UiItem.builder()
                .itemId(itemId)
                .kind(UiItemKind.TOOL_CALL)
                .contentIndex(contentIndex)
                .body(new UiItemBodies.ToolCall(toolCall))
                .build();
    }

    private static UiItem toolResultItem(
            ToolResultMessage toolResult,
            String sourceItemId,
            Integer contentIndex,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs
    ) {
        return UiItem.builder()
                .itemId(toolResult.id())
                .kind(UiItemKind.TOOL_RESULT)
                .contentIndex(contentIndex)
                .body(new UiItemBodies.ToolResult(uiToolResult(
                        toolResult,
                        sourceItemId,
                        contentIndex,
                        status,
                        durationMs,
                        approvalWaitMs,
                        executionDurationMs
                )))
                .build();
    }

    private static UiToolCall uiToolCall(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            Tool toolDefinition
    ) {
        if (toolCall == null) {
            return null;
        }
        Object arguments = toolArguments(toolCall);
        return UiToolCall.builder()
                .itemId(itemId)
                .contentIndex(contentIndex)
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .arguments(arguments)
                .displayName(toolDisplayName(toolCall, toolDefinition))
                .displaySummary(toolDisplaySummary(toolCall, arguments))
                .riskLevel(toolDefinition == null || toolDefinition.riskLevel() == null
                        ? null
                        : toolDefinition.riskLevel().name())
                .build();
    }

    private static UiToolResult uiToolResult(
            ToolResultMessage message,
            String sourceItemId,
            Integer contentIndex,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs
    ) {
        if (message == null) {
            return null;
        }
        return UiToolResult.builder()
                .itemId(message.id())
                .sourceItemId(sourceItemId)
                .contentIndex(contentIndex)
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .text(MessageContents.text(message))
                .error(message.isError())
                .status(status == null ? (message.isError() ? "FAILED" : "COMPLETED") : status)
                .durationMs(durationMs)
                .approvalWaitMs(approvalWaitMs)
                .executionDurationMs(executionDurationMs)
                .details(normalizeToolDetails(message.getToolName(), message.getDetails()))
                .display(message.getDisplay())
                .truncated(truncated(message.getDetails()))
                .build();
    }

    private static UiToolUpdate uiToolUpdate(
            ToolResultMessage message,
            String sourceItemId,
            Integer contentIndex,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs
    ) {
        if (message == null) {
            return null;
        }
        return UiToolUpdate.builder()
                .itemId(message.id())
                .sourceItemId(sourceItemId)
                .contentIndex(contentIndex)
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .text(MessageContents.text(message))
                .status(status == null ? (message.isError() ? "FAILED" : "COMPLETED") : status)
                .durationMs(durationMs)
                .approvalWaitMs(approvalWaitMs)
                .executionDurationMs(executionDurationMs)
                .details(normalizeToolDetails(message.getToolName(), message.getDetails()))
                .display(message.getDisplay())
                .truncated(truncated(message.getDetails()))
                .build();
    }

    private static UiToolUpdate uiToolUpdate(
            String itemId,
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolDisplayResult result,
            String status,
            Long durationMs
    ) {
        return uiToolUpdate(itemId, sourceItemId, contentIndex, toolCall, result, status, durationMs, null, null);
    }

    private static UiToolUpdate uiToolUpdate(
            String itemId,
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolDisplayResult result,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs
    ) {
        if (result == null) {
            return UiToolUpdate.builder()
                    .itemId(itemId)
                    .sourceItemId(sourceItemId)
                    .contentIndex(contentIndex)
                    .toolCallId(toolCall == null ? null : toolCall.getToolCallId())
                    .toolName(toolCall == null ? null : toolCall.getToolName())
                    .status(status)
                    .durationMs(durationMs)
                    .approvalWaitMs(approvalWaitMs)
                    .executionDurationMs(executionDurationMs)
                    .build();
        }
        String toolName = toolCall == null ? null : toolCall.getToolName();
        return UiToolUpdate.builder()
                .itemId(itemId)
                .sourceItemId(sourceItemId)
                .contentIndex(contentIndex)
                .toolCallId(toolCall == null ? null : toolCall.getToolCallId())
                .toolName(toolName)
                .text(result.text())
                .status(status)
                .durationMs(durationMs)
                .approvalWaitMs(approvalWaitMs)
                .executionDurationMs(executionDurationMs)
                .details(normalizeToolDetails(toolName, result.data()))
                .display(result)
                .truncated(truncated(result.data()))
                .build();
    }

    private static Object toolArguments(ToolCallContent toolCall) {
        if (toolCall.getArguments() != null
                && !toolCall.getArguments().isMissingNode()
                && !toolCall.getArguments().isNull()) {
            return toolCall.getArguments();
        }
        return null;
    }

    private static String toolDisplayName(ToolCallContent toolCall, Tool toolDefinition) {
        if (toolDefinition != null && toolDefinition.label() != null && !toolDefinition.label().isBlank()) {
            return toolDefinition.label();
        }
        return toolCall.getToolName();
    }

    private static String toolDisplaySummary(ToolCallContent toolCall, Object arguments) {
        String toolName = toolCall.getToolName();
        if (arguments instanceof JsonNode node && node.isObject()) {
            return switch (toolName == null ? "" : toolName) {
                case "Bash", "bash", "PowerShell", "powershell" -> jsonText(node, "command");
                case "Read", "read", "Write", "write", "Edit", "edit" -> jsonText(node, "file_path");
                case "Glob", "glob" -> joinSummary(
                        jsonText(node, "pattern"),
                        prefixed("in ", jsonText(node, "path"))
                );
                case "Grep", "grep" -> joinSummary(
                        jsonText(node, "pattern"),
                        prefixed("in ", jsonText(node, "path")),
                        parenthesized(jsonText(node, "glob"))
                );
                default -> null;
            };
        }
        if (!(arguments instanceof Map<?, ?> map)) {
            return null;
        }
        return switch (toolName == null ? "" : toolName) {
            case "Bash", "bash", "PowerShell", "powershell" -> stringValue(map.get("command"));
            case "Read", "read", "Write", "write", "Edit", "edit" -> stringValue(map.get("file_path"));
            case "Glob", "glob" -> joinSummary(
                    stringValue(map.get("pattern")),
                    prefixed("in ", stringValue(map.get("path")))
            );
            case "Grep", "grep" -> joinSummary(
                    stringValue(map.get("pattern")),
                    prefixed("in ", stringValue(map.get("path"))),
                    parenthesized(stringValue(map.get("glob")))
            );
            default -> null;
        };
    }

    private static Object normalizeToolDetails(String toolName, Object details) {
        if (!(details instanceof Map<?, ?> input)) {
            return details;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                output.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        output.put("kind", toolName == null || toolName.isBlank() ? stringValue(input.get("kind")) : toolName);
        return output;
    }

    private static String joinSummary(String... parts) {
        StringBuilder summary = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(' ');
            }
            summary.append(part.trim());
        }
        return summary.length() == 0 ? null : summary.toString();
    }

    private static String prefixed(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + value.trim();
    }

    private static String parenthesized(String value) {
        return value == null || value.isBlank() ? null : "(" + value.trim() + ")";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static String jsonText(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static Boolean truncated(Object details) {
        if (details instanceof Map<?, ?> map && map.get("truncated") instanceof Boolean truncated) {
            return truncated;
        }
        return null;
    }

    private static UiApprovalRequest uiApprovalRequest(ApprovalRequest request) {
        if (request == null) {
            return null;
        }
        return UiApprovalRequest.builder()
                .approvalId(request.id().value())
                .toolCallId(request.toolCallId())
                .toolName(request.toolName())
                .riskLevel(request.riskLevel() == null ? null : request.riskLevel().name())
                .arguments(request.arguments())
                .reason(request.reason())
                .build();
    }

    private static UiApprovalResponse uiApprovalResponse(ApprovalResponse response) {
        if (response == null) {
            return null;
        }
        return UiApprovalResponse.builder()
                .approvalId(response.id().value())
                .approved(response.approved())
                .reason(response.reason())
                .build();
    }

    private static UiTokenUsage uiTokenUsage(
            TokenUsageInfo tokenUsageInfo,
            long contextTokenUsage,
            Long autoCompactTokenLimit
    ) {
        return UiTokenUsage.builder()
                .total(uiTokenCount(tokenUsageInfo == null ? null : tokenUsageInfo.totalTokenUsage()))
                .last(uiTokenCount(tokenUsageInfo == null ? null : tokenUsageInfo.lastTokenUsage()))
                .modelContextWindow(tokenUsageInfo == null ? null : tokenUsageInfo.modelContextWindow())
                .contextTokenUsage(contextTokenUsage)
                .autoCompactTokenLimit(autoCompactTokenLimit)
                .build();
    }

    private static UiModelSelection uiModelSelection(ModelSelection selection) {
        ModelInfo model = selection == null ? null : selection.model();
        var endpoint = selection == null ? null : selection.endpoint();
        return new UiModelSelection(
                endpoint == null ? null : endpoint.providerId(),
                model == null ? null : model.getId(),
                model == null ? null : model.getName(),
                selection == null || selection.reasoning() == null || selection.reasoning().getReasoningEffort() == null
                        ? null
                        : selection.reasoning().getReasoningEffort().name()
        );
    }

    private static UiPermissionMode uiPermissionMode(PermissionPreset preset, PermissionPreset current) {
        PermissionPreset normalized = preset == null ? PermissionPreset.DEFAULT : preset;
        return new UiPermissionMode(
                normalized.name(),
                permissionName(normalized),
                permissionDescription(normalized),
                normalized == (current == null ? PermissionPreset.DEFAULT : current)
        );
    }

    private static String permissionName(PermissionPreset preset) {
        return switch (preset) {
            case DEFAULT -> "Default";
            case FULL_ACCESS -> "Full Access";
        };
    }

    private static String permissionDescription(PermissionPreset preset) {
        return switch (preset) {
            case DEFAULT -> "Workspace writes are allowed; shell commands and outside-workspace writes ask first.";
            case FULL_ACCESS -> "Allow tools without approval, including shell commands and outside-workspace edits.";
        };
    }

    private static UiTokenCount uiTokenCount(TokenUsage usage) {
        TokenUsage normalized = usage == null ? TokenUsage.empty() : usage;
        return UiTokenCount.builder()
                .inputTokens(normalized.inputTokens())
                .cachedInputTokens(normalized.cachedInputTokens())
                .outputTokens(normalized.outputTokens())
                .reasoningOutputTokens(normalized.reasoningOutputTokens())
                .totalTokens(normalized.totalTokens())
                .build();
    }
}
