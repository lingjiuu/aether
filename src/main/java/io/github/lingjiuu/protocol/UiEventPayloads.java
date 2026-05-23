package io.github.lingjiuu.protocol;

public final class UiEventPayloads {

    private UiEventPayloads() {
    }

    public record Text(String text) implements UiEventPayload {
    }

    public record SessionName(String sessionId, String name) implements UiEventPayload {
    }

    public record UserMessage(UiItem item) implements UiEventPayload {
    }

    public record ContextMessage(UiItem item) implements UiEventPayload {
    }

    public record ItemStarted(
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            UiToolCall toolCall
    ) implements UiEventPayload {
    }

    public record ItemCompleted(UiItem item) implements UiEventPayload {
    }

    public record TextDelta(
            UiItemKind itemKind,
            String itemId,
            Integer contentIndex,
            String delta
    ) implements UiEventPayload {
    }

    public record ToolArgumentsDelta(
            String itemId,
            Integer contentIndex,
            UiToolCall toolCall,
            String delta
    ) implements UiEventPayload {
    }

    public record ToolArgumentsDone(UiItem item) implements UiEventPayload {
    }

    public record ToolCall(UiToolCall toolCall) implements UiEventPayload {
    }

    public record ToolExecution(
            UiToolCall toolCall,
            UiToolResult toolResult
    ) implements UiEventPayload {
    }

    public record ToolResult(UiItem item) implements UiEventPayload {
    }

    public record Approval(
            UiApprovalRequest request,
            UiApprovalResponse response
    ) implements UiEventPayload {
    }

    public record TokenUsage(UiTokenUsage tokenUsage) implements UiEventPayload {
    }

    public record Compact(
            String text,
            Integer originalMessageCount,
            Integer replacementMessageCount
    ) implements UiEventPayload {
    }

    public record Error(String message) implements UiEventPayload {
    }
}
