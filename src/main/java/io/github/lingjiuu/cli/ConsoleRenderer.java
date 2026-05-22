package io.github.lingjiuu.cli;

import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayload;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiItem;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiHistory;
import io.github.lingjiuu.protocol.UiHistoryItem;
import io.github.lingjiuu.protocol.UiToolCall;
import io.github.lingjiuu.protocol.UiToolResult;
import io.github.lingjiuu.protocol.UiTurn;

public class ConsoleRenderer implements EventSink {

    private boolean assistantTextLineOpen;

    @Override
    public void onEvent(UiEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        if (event.getType() != io.github.lingjiuu.protocol.UiEventType.ASSISTANT_TEXT_DELTA) {
            finishAssistantTextLine();
        }
        switch (event.getType()) {
            case USER_MESSAGE -> {
                System.out.println("[USER] " + text(event));
            }
            case TURN_STARTED -> {
                System.out.println();
                System.out.println("[AGENT] turn " + event.getTurn() + " start");
            }
            case ITEM_STARTED -> {
            }
            case ITEM_COMPLETED -> {
            }
            case ASSISTANT_TEXT_DELTA -> {
                String delta = delta(event);
                if (delta != null) {
                    printAssistantDelta(delta);
                }
            }
            case REASONING_DELTA -> {
            }
            case TOOL_CALL_ARGUMENTS_DELTA -> {
            }
            case TOOL_CALL_ARGUMENTS_DONE -> {
            }
            case TOKEN_USAGE -> {
                if (event.getPayload() instanceof UiEventPayloads.TokenUsage tokenUsagePayload
                        && tokenUsagePayload.tokenUsage() != null
                        && tokenUsagePayload.tokenUsage().getContextTokenUsage() != null) {
                    String limit = tokenUsagePayload.tokenUsage().getAutoCompactTokenLimit() == null
                            ? "off"
                            : tokenUsagePayload.tokenUsage().getAutoCompactTokenLimit().toString();
                    System.out.println("[TOKENS] context="
                            + tokenUsagePayload.tokenUsage().getContextTokenUsage()
                            + " auto_compact_at="
                            + limit);
                }
            }
            case TOOL_CALL -> {
                UiToolCall toolCall = toolCall(event);
                if (toolCall != null) {
                    System.out.println("[TOOL] call_id=" + toolCall.getToolCallId());
                    System.out.println("[TOOL] " + toolCall.getToolName()
                            + " " + toolCall.getArgumentsJson());
                }
            }
            case TOOL_EXECUTION_STARTED -> {
                UiToolCall toolCall = toolCall(event);
                if (toolCall != null) {
                    System.out.println("[TOOL] executing " + toolCall.getToolName());
                }
            }
            case TOOL_EXECUTION_UPDATE -> {
            }
            case TOOL_EXECUTION_FINISHED -> {
            }
            case TOOL_RESULT -> {
                UiToolResult toolResult = toolResult(event);
                if (toolResult != null) {
                    System.out.println("[TOOL] result=" + toolResult.getText());
                }
            }
            case APPROVAL_REQUESTED -> {
                if (event.getPayload() instanceof UiEventPayloads.Approval approval
                        && approval.request() != null) {
                    System.out.println("[APPROVAL] requested for " + approval.request().getToolName());
                    System.out.println("[APPROVAL] risk=" + approval.request().getRiskLevel());
                    if (approval.request().getReason() != null
                            && !approval.request().getReason().isBlank()) {
                        System.out.println("[APPROVAL] reason=" + approval.request().getReason());
                    }
                    System.out.println("[APPROVAL] args=" + approval.request().getArguments());
                }
            }
            case APPROVAL_RESOLVED -> {
                if (event.getPayload() instanceof UiEventPayloads.Approval approval
                        && approval.response() != null) {
                    System.out.println("[APPROVAL] "
                            + (approval.response().isApproved() ? "approved" : "denied"));
                    if (approval.response().getReason() != null
                            && !approval.response().getReason().isBlank()) {
                        System.out.println("[APPROVAL] reason=" + approval.response().getReason());
                    }
                }
            }
            case CONTEXT_MESSAGE -> {
                String text = text(event);
                if (text != null && !text.isBlank()) {
                    System.out.println("[CONTEXT] " + text);
                }
            }
            case TURN_COMPLETED -> System.out.println("[AGENT] turn complete");
            case TURN_ABORTED -> System.out.println("[AGENT] turn interrupted");
            case COMPACT_STARTED -> {
                UiEventPayloads.Compact compact = compact(event);
                String trigger = compact == null || compact.text() == null || compact.text().isBlank()
                        ? ""
                        : " (" + compact.text() + ")";
                System.out.println("[CONTEXT] compact start" + trigger);
                if (compact != null && compact.originalMessageCount() != null) {
                    System.out.println("[CONTEXT] original messages=" + compact.originalMessageCount());
                }
            }
            case COMPACT_FINISHED -> {
                UiEventPayloads.Compact compact = compact(event);
                System.out.println("[CONTEXT] compact finished");
                if (compact != null
                        && compact.originalMessageCount() != null
                        && compact.replacementMessageCount() != null) {
                    System.out.println("[CONTEXT] messages "
                            + compact.originalMessageCount()
                            + " -> "
                            + compact.replacementMessageCount());
                }
            }
            case COMPACT_SKIPPED -> {
                UiEventPayloads.Compact compact = compact(event);
                System.out.println("[CONTEXT] compact skipped");
                if (compact != null && compact.text() != null && !compact.text().isBlank()) {
                    System.out.println("[CONTEXT] " + compact.text());
                }
            }
            case SESSION_RESET -> System.out.println("[SESSION] reset");
            case SKILLS_CHANGED -> {
                String text = text(event);
                text = text == null || text.isBlank()
                        ? "skills changed"
                        : text;
                System.out.println("[SKILLS] " + text);
            }
            case ERROR -> {
                if (event.getPayload() instanceof UiEventPayloads.Error error
                        && error.message() != null
                        && !error.message().isBlank()) {
                    System.out.println("[ERROR] " + error.message());
                }
            }
        }
    }

    public void renderHistory(UiHistory history) {
        if (history == null || history.turns() == null || history.turns().isEmpty()) {
            return;
        }
        for (UiTurn turn : history.turns()) {
            System.out.println();
            System.out.println("[AGENT] turn " + turn.turn() + " " + turn.status().toLowerCase());
            for (UiHistoryItem item : turn.items()) {
                renderHistoryItem(item);
            }
        }
    }

    private void printAssistantDelta(String delta) {
        if (delta.isEmpty()) {
            return;
        }
        System.out.print(delta);
        assistantTextLineOpen = !delta.endsWith("\n");
    }

    private void finishAssistantTextLine() {
        if (assistantTextLineOpen) {
            System.out.println();
            assistantTextLineOpen = false;
        }
    }

    private String text(UiEvent event) {
        UiEventPayload payload = event.getPayload();
        if (payload instanceof UiEventPayloads.Text text) {
            return text.text();
        }
        if (payload instanceof UiEventPayloads.UserMessage userMessage) {
            return itemText(userMessage.item());
        }
        if (payload instanceof UiEventPayloads.ContextMessage contextMessage) {
            return itemText(contextMessage.item());
        }
        if (payload instanceof UiEventPayloads.ItemCompleted itemCompleted) {
            return itemText(itemCompleted.item());
        }
        return null;
    }

    private String delta(UiEvent event) {
        UiEventPayload payload = event.getPayload();
        if (payload instanceof UiEventPayloads.TextDelta textDelta) {
            return textDelta.delta();
        }
        if (payload instanceof UiEventPayloads.ToolArgumentsDelta toolDelta) {
            return toolDelta.delta();
        }
        return null;
    }

    private UiToolCall toolCall(UiEvent event) {
        UiEventPayload payload = event.getPayload();
        if (payload instanceof UiEventPayloads.ToolCall toolCall) {
            return toolCall.toolCall();
        }
        if (payload instanceof UiEventPayloads.ToolExecution toolExecution) {
            return toolExecution.toolCall();
        }
        if (payload instanceof UiEventPayloads.ToolArgumentsDelta toolDelta) {
            return toolDelta.toolCall();
        }
        if (payload instanceof UiEventPayloads.ToolArgumentsDone toolDone) {
            return itemToolCall(toolDone.item());
        }
        if (payload instanceof UiEventPayloads.ItemCompleted itemCompleted) {
            return itemToolCall(itemCompleted.item());
        }
        return null;
    }

    private UiToolResult toolResult(UiEvent event) {
        UiEventPayload payload = event.getPayload();
        if (payload instanceof UiEventPayloads.ToolResult toolResult) {
            return itemToolResult(toolResult.item());
        }
        if (payload instanceof UiEventPayloads.ToolExecution toolExecution) {
            return toolExecution.toolResult();
        }
        return null;
    }

    private UiEventPayloads.Compact compact(UiEvent event) {
        return event.getPayload() instanceof UiEventPayloads.Compact compact ? compact : null;
    }

    private String itemText(UiItem item) {
        if (item != null && item.getBody() instanceof UiItemBodies.Text text) {
            return text.text();
        }
        return null;
    }

    private UiToolCall itemToolCall(UiItem item) {
        if (item != null && item.getBody() instanceof UiItemBodies.ToolCall toolCall) {
            return toolCall.toolCall();
        }
        return null;
    }

    private UiToolResult itemToolResult(UiItem item) {
        if (item != null && item.getBody() instanceof UiItemBodies.ToolResult toolResult) {
            return toolResult.toolResult();
        }
        return null;
    }

    private void renderHistoryItem(UiHistoryItem item) {
        if (item == null || item.kind() == null) {
            return;
        }
        switch (item.kind()) {
            case USER_MESSAGE -> printIfNotBlank("[USER] ", item.text());
            case CONTEXT_MESSAGE -> printIfNotBlank("[CONTEXT] ", item.text());
            case ASSISTANT_TEXT -> printIfNotBlank("", item.text());
            case REASONING -> {
            }
            case TOOL_CALL -> {
                if (item.toolCall() != null) {
                    System.out.println("[TOOL] call_id=" + item.toolCall().getToolCallId());
                    System.out.println("[TOOL] " + item.toolCall().getToolName()
                            + " " + item.toolCall().getArgumentsJson());
                }
                if (item.toolResult() != null) {
                    System.out.println("[TOOL] result=" + item.toolResult().getText());
                }
            }
            case TOOL_RESULT -> {
                if (item.toolResult() != null) {
                    System.out.println("[TOOL] result=" + item.toolResult().getText());
                }
            }
        }
    }

    private void printIfNotBlank(String prefix, String text) {
        if (text != null && !text.isBlank()) {
            System.out.println(prefix + text);
        }
    }
}
