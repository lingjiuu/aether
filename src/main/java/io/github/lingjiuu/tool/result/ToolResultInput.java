package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;

public record ToolResultInput(
        ToolCallContent toolCall,
        Tool<?, ?> tool,
        Object input,
        ToolCallResult<?> callResult,
        String status,
        Long durationMs
) {
}
