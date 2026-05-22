package io.github.lingjiuu.protocol;

public record UiHistoryItem(
        String id,
        UiItemKind kind,
        String status,
        Integer contentIndex,
        String text,
        UiToolCall toolCall,
        UiToolResult toolResult
) {
}
