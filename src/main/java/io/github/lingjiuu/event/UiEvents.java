package io.github.lingjiuu.event;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.llm.TokenUsage;
import io.github.lingjiuu.llm.TokenUsageInfo;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.protocol.UiApprovalRequest;
import io.github.lingjiuu.protocol.UiApprovalResponse;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiItem;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.protocol.UiTokenCount;
import io.github.lingjiuu.protocol.UiTokenUsage;
import io.github.lingjiuu.protocol.UiToolCall;
import io.github.lingjiuu.protocol.UiToolResult;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;

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
            ToolCallContent toolCall
    ) {
        return event(UiEventType.ITEM_STARTED, turnContext)
                .payload(new UiEventPayloads.ItemStarted(
                        itemKind,
                        itemId,
                        contentIndex,
                        uiToolCall(itemId, contentIndex, toolCall)
                ))
                .build();
    }

    public static UiEvent itemCompleted(
            TurnContext turnContext,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            String text
    ) {
        UiItem item = itemKind == UiItemKind.TOOL_CALL
                ? toolCallItem(itemId, contentIndex, uiToolCall(itemId, contentIndex, toolCall))
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
            String delta
    ) {
        return event(UiEventType.TOOL_CALL_ARGUMENTS_DELTA, turnContext)
                .payload(new UiEventPayloads.ToolArgumentsDelta(
                        itemId,
                        contentIndex,
                        uiToolCall(itemId, contentIndex, toolCall),
                        delta
                ))
                .build();
    }

    public static UiEvent toolArgumentsDone(
            TurnContext turnContext,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall
    ) {
        return event(UiEventType.TOOL_CALL_ARGUMENTS_DONE, turnContext)
                .payload(new UiEventPayloads.ToolArgumentsDone(toolCallItem(
                        itemId,
                        contentIndex,
                        uiToolCall(itemId, contentIndex, toolCall)
                )))
                .build();
    }

    public static UiEvent toolCall(String itemId, Integer contentIndex, ToolCallContent toolCall, TurnContext turnContext) {
        return event(UiEventType.TOOL_CALL, turnContext)
                .payload(new UiEventPayloads.ToolCall(uiToolCall(itemId, contentIndex, toolCall)))
                .build();
    }

    public static UiEvent toolExecutionBegin(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_EXECUTION_BEGIN, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(itemId, contentIndex, toolCall),
                        uiToolResult(itemId, itemId, contentIndex, toolCall, null, "RUNNING", null)
                ))
                .build();
    }

    public static UiEvent toolExecutionUpdate(
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolExecutionResult partialResult,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_EXECUTION_UPDATE, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(itemId, contentIndex, toolCall),
                        uiToolResult(itemId, itemId, contentIndex, toolCall, partialResult, "RUNNING", null)
                ))
                .build();
    }

    public static UiEvent toolExecutionEnd(
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            String status,
            Long durationMs,
            TurnContext turnContext
    ) {
        if (toolResult == null) {
            return null;
        }
        return event(UiEventType.TOOL_EXECUTION_END, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(sourceItemId, contentIndex, toolCall),
                        uiToolResult(toolResult, sourceItemId, contentIndex, status, durationMs)
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
                        durationMs
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
            Long durationMs
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
                        durationMs
                )))
                .build();
    }

    private static UiToolCall uiToolCall(String itemId, Integer contentIndex, ToolCallContent toolCall) {
        if (toolCall == null) {
            return null;
        }
        return UiToolCall.builder()
                .itemId(itemId)
                .contentIndex(contentIndex)
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .build();
    }

    private static UiToolResult uiToolResult(
            ToolResultMessage message,
            String sourceItemId,
            Integer contentIndex,
            String status,
            Long durationMs
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
                .details(message.getDetails())
                .truncated(truncated(message.getDetails()))
                .build();
    }

    private static UiToolResult uiToolResult(
            String itemId,
            String sourceItemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            ToolExecutionResult result,
            String status,
            Long durationMs
    ) {
        if (result == null) {
            return UiToolResult.builder()
                    .sourceItemId(sourceItemId)
                    .contentIndex(contentIndex)
                    .toolCallId(toolCall == null ? null : toolCall.getToolCallId())
                    .toolName(toolCall == null ? null : toolCall.getToolName())
                    .status(status)
                    .durationMs(durationMs)
                    .build();
        }
        return UiToolResult.builder()
                .itemId(itemId)
                .sourceItemId(sourceItemId)
                .contentIndex(contentIndex)
                .toolCallId(toolCall == null ? null : toolCall.getToolCallId())
                .toolName(toolCall == null ? null : toolCall.getToolName())
                .text(toolExecutionText(result))
                .error(result.isError())
                .status(status == null ? (result.isError() ? "FAILED" : "COMPLETED") : status)
                .durationMs(durationMs)
                .details(result.getDetails())
                .truncated(truncated(result.getDetails()))
                .build();
    }

    private static Boolean truncated(Object details) {
        if (details instanceof Map<?, ?> map && map.get("truncated") instanceof Boolean truncated) {
            return truncated;
        }
        return null;
    }

    private static String toolExecutionText(ToolExecutionResult result) {
        StringBuilder text = new StringBuilder();
        for (MessageContent content : result.getContents()) {
            if (content instanceof TextContent textContent
                    && textContent.getText() != null
                    && !textContent.getText().isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.getText());
            }
        }
        return text.toString().trim();
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
