package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiHistoryItem(
        String id,
        UiItemKind kind,
        String status,
        Integer contentIndex,
        String text,
        UiToolCall toolCall,
        UiToolUpdate toolUpdate,
        UiToolResult toolResult
) {
    public UiHistoryItem(
            String id,
            UiItemKind kind,
            String status,
            Integer contentIndex,
            String text,
            UiToolCall toolCall,
            UiToolResult toolResult
    ) {
        this(id, kind, status, contentIndex, text, toolCall, null, toolResult);
    }
}
