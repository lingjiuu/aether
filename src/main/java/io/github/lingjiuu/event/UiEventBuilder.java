package io.github.lingjiuu.event;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.llm.TokenUsage;
import io.github.lingjiuu.llm.TokenUsageInfo;
import io.github.lingjiuu.message.AssistantMessage;
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

final class UiEventBuilder {

    UiEvent runStarted(String sessionId, int turn) {
        return event(UiEventType.RUN_STARTED, sessionId, turn).build();
    }

    UiEvent runFinished(String sessionId, int turn) {
        return event(UiEventType.RUN_FINISHED, sessionId, turn).build();
    }

    UiEvent turnStarted(TurnContext turnContext) {
        return event(UiEventType.TURN_STARTED, turnContext).build();
    }

    UiEvent turnAborted(TurnContext turnContext) {
        return event(UiEventType.TURN_ABORTED, turnContext).build();
    }

    UiEvent sessionReset(String sessionId) {
        return event(UiEventType.SESSION_RESET, sessionId, null)
                .payload(new UiEventPayloads.Text("session reset"))
                .build();
    }

    UiEvent skillsChanged(String sessionId, int availableSkillCount) {
        return event(UiEventType.SKILLS_CHANGED, sessionId, null)
                .payload(new UiEventPayloads.Text("skills changed: " + availableSkillCount))
                .build();
    }

    UiEvent userMessage(UserMessage userMessage, TurnContext turnContext) {
        if (userMessage == null) {
            return null;
        }
        UiItem item = textItem(userMessage.id(), UiItemKind.USER_MESSAGE, null, MessageContents.text(userMessage));
        return event(UiEventType.USER_MESSAGE, turnContext)
                .payload(new UiEventPayloads.UserMessage(item))
                .build();
    }

    UiEvent contextMessage(ContextMessage contextMessage, TurnContext turnContext) {
        if (contextMessage == null) {
            return null;
        }
        UiItem item = textItem(contextMessage.id(), UiItemKind.CONTEXT_MESSAGE, null, MessageContents.text(contextMessage));
        return event(UiEventType.CONTEXT_MESSAGE, turnContext)
                .payload(new UiEventPayloads.ContextMessage(item))
                .build();
    }

    UiEvent itemStarted(
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
                        uiToolCall(toolCall)
                ))
                .build();
    }

    UiEvent itemCompleted(
            TurnContext turnContext,
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall,
            String text
    ) {
        UiItem item = itemKind == UiItemKind.TOOL_CALL
                ? toolCallItem(itemId, contentIndex, uiToolCall(toolCall))
                : textItem(itemId, itemKind, contentIndex, text);
        return event(UiEventType.ITEM_COMPLETED, turnContext)
                .payload(new UiEventPayloads.ItemCompleted(item))
                .build();
    }

    UiEvent assistantTextDelta(TurnContext turnContext, String itemId, Integer contentIndex, String delta) {
        return textDelta(turnContext, UiEventType.ASSISTANT_TEXT_DELTA, UiItemKind.ASSISTANT_TEXT, itemId, contentIndex, delta);
    }

    UiEvent reasoningDelta(TurnContext turnContext, String itemId, Integer contentIndex, String delta) {
        return textDelta(turnContext, UiEventType.REASONING_DELTA, UiItemKind.REASONING, itemId, contentIndex, delta);
    }

    UiEvent toolArgumentsDelta(
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
                        uiToolCall(toolCall),
                        delta
                ))
                .build();
    }

    UiEvent toolArgumentsDone(
            TurnContext turnContext,
            String itemId,
            Integer contentIndex,
            ToolCallContent toolCall
    ) {
        return event(UiEventType.TOOL_CALL_ARGUMENTS_DONE, turnContext)
                .payload(new UiEventPayloads.ToolArgumentsDone(toolCallItem(
                        itemId,
                        contentIndex,
                        uiToolCall(toolCall)
                )))
                .build();
    }

    UiEvent toolCall(ToolCallContent toolCall, TurnContext turnContext) {
        return event(UiEventType.TOOL_CALL, turnContext)
                .payload(new UiEventPayloads.ToolCall(uiToolCall(toolCall)))
                .build();
    }

    UiEvent toolExecutionStarted(ToolCallContent toolCall, TurnContext turnContext) {
        return event(UiEventType.TOOL_EXECUTION_STARTED, turnContext)
                .payload(new UiEventPayloads.ToolExecution(uiToolCall(toolCall), null))
                .build();
    }

    UiEvent toolExecutionUpdate(
            ToolCallContent toolCall,
            ToolExecutionResult partialResult,
            TurnContext turnContext
    ) {
        return event(UiEventType.TOOL_EXECUTION_UPDATE, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(toolCall),
                        uiToolResult(toolCall, partialResult)
                ))
                .build();
    }

    UiEvent toolExecutionFinished(
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext
    ) {
        if (toolResult == null) {
            return null;
        }
        return event(UiEventType.TOOL_EXECUTION_FINISHED, turnContext)
                .payload(new UiEventPayloads.ToolExecution(
                        uiToolCall(toolCall),
                        uiToolResult(toolResult)
                ))
                .build();
    }

    UiEvent toolResult(
            ToolCallContent toolCall,
            ToolResultMessage toolResult,
            TurnContext turnContext
    ) {
        if (toolResult == null) {
            return null;
        }
        return event(UiEventType.TOOL_RESULT, turnContext)
                .payload(new UiEventPayloads.ToolResult(toolResultItem(toolResult)))
                .build();
    }

    UiEvent finalAnswer(AssistantMessage assistantMessage, TurnContext turnContext) {
        String text = assistantMessage == null ? "" : MessageContents.text(assistantMessage);
        String itemId = assistantMessage == null ? null : assistantMessage.id();
        return event(UiEventType.FINAL_ANSWER, turnContext)
                .payload(new UiEventPayloads.ItemCompleted(textItem(
                        itemId,
                        UiItemKind.ASSISTANT_TEXT,
                        null,
                        text
                )))
                .build();
    }

    UiEvent error(TurnContext turnContext, String message) {
        return event(UiEventType.ERROR, turnContext)
                .payload(new UiEventPayloads.Error(message))
                .build();
    }

    UiEvent error(String sessionId, int turn, String message) {
        return event(UiEventType.ERROR, sessionId, turn)
                .payload(new UiEventPayloads.Error(message))
                .build();
    }

    UiEvent approvalRequested(ApprovalRequest request, TurnContext turnContext) {
        return event(UiEventType.APPROVAL_REQUESTED, turnContext)
                .payload(new UiEventPayloads.Approval(uiApprovalRequest(request), null))
                .build();
    }

    UiEvent approvalResolved(ApprovalRequest request, ApprovalResponse response, TurnContext turnContext) {
        return event(UiEventType.APPROVAL_RESOLVED, turnContext)
                .payload(new UiEventPayloads.Approval(uiApprovalRequest(request), uiApprovalResponse(response)))
                .build();
    }

    UiEvent tokenUsage(
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

    UiEvent compactStarted(TurnContext turnContext, String trigger, int originalMessageCount) {
        return compactEvent(
                UiEventType.COMPACT_STARTED,
                turnContext,
                trigger,
                originalMessageCount,
                null
        );
    }

    UiEvent compactSkipped(
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

    UiEvent compactFinished(
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

    private UiEvent textDelta(
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

    private UiEvent compactEvent(
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

    private UiEvent.UiEventBuilder event(UiEventType type, TurnContext turnContext) {
        return event(type, turnContext.sessionId(), turnContext.turn());
    }

    private UiEvent.UiEventBuilder event(UiEventType type, String sessionId, Integer turn) {
        return UiEvent.builder()
                .type(type)
                .sessionId(sessionId)
                .turn(turn);
    }

    private UiItem textItem(String itemId, UiItemKind kind, Integer contentIndex, String text) {
        return UiItem.builder()
                .itemId(itemId)
                .kind(kind)
                .contentIndex(contentIndex)
                .body(new UiItemBodies.Text(text))
                .build();
    }

    private UiItem toolCallItem(String itemId, Integer contentIndex, UiToolCall toolCall) {
        return UiItem.builder()
                .itemId(itemId)
                .kind(UiItemKind.TOOL_CALL)
                .contentIndex(contentIndex)
                .body(new UiItemBodies.ToolCall(toolCall))
                .build();
    }

    private UiItem toolResultItem(ToolResultMessage toolResult) {
        return UiItem.builder()
                .itemId(toolResult.id())
                .kind(UiItemKind.TOOL_RESULT)
                .body(new UiItemBodies.ToolResult(uiToolResult(toolResult)))
                .build();
    }

    private UiToolCall uiToolCall(ToolCallContent toolCall) {
        if (toolCall == null) {
            return null;
        }
        return UiToolCall.builder()
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .build();
    }

    private UiToolResult uiToolResult(ToolResultMessage message) {
        if (message == null) {
            return null;
        }
        return UiToolResult.builder()
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .text(MessageContents.text(message))
                .error(message.isError())
                .build();
    }

    private UiToolResult uiToolResult(ToolCallContent toolCall, ToolExecutionResult result) {
        if (result == null) {
            return null;
        }
        return UiToolResult.builder()
                .toolCallId(toolCall == null ? null : toolCall.getToolCallId())
                .toolName(toolCall == null ? null : toolCall.getToolName())
                .text(toolExecutionText(result))
                .error(result.isError())
                .build();
    }

    private String toolExecutionText(ToolExecutionResult result) {
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

    private UiApprovalRequest uiApprovalRequest(ApprovalRequest request) {
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

    private UiApprovalResponse uiApprovalResponse(ApprovalResponse response) {
        if (response == null) {
            return null;
        }
        return UiApprovalResponse.builder()
                .approvalId(response.id().value())
                .approved(response.approved())
                .reason(response.reason())
                .build();
    }

    private UiTokenUsage uiTokenUsage(
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

    private UiTokenCount uiTokenCount(TokenUsage usage) {
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
