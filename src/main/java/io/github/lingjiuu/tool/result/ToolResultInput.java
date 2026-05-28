package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;

public record ToolResultInput(
        ToolCallContent toolCall,
        Tool tool,
        ToolExecutionResult executionResult
) {
}
