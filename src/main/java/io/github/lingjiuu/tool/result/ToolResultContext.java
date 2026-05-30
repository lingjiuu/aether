package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolCallStatus;

public record ToolResultContext<I, O>(
        ToolCallContent toolCall,
        I input,
        ToolCallStatus status,
        Long durationMs,
        Long approvalWaitMs,
        Long executionDurationMs
) {
}
