package io.github.lingjiuu.tool.render;

import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.util.Map;

public record ToolRenderRequest(
        String toolName,
        String toolCallId,
        String argumentsJson,
        Map<String, Object> arguments,
        ToolCallContent toolCall,
        ToolResultMessage toolResult,
        ToolExecutionResult partialResult,
        boolean partial,
        boolean expanded
) {

    public ToolRenderRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolRenderRequest forCall(ToolCallContent toolCall, Map<String, Object> arguments) {
        return new ToolRenderRequest(
                toolCall == null ? null : toolCall.getToolName(),
                toolCall == null ? null : toolCall.getToolCallId(),
                toolCall == null ? null : toolCall.getArgumentsJson(),
                arguments,
                toolCall,
                null,
                null,
                true,
                false
        );
    }

    public static ToolRenderRequest forResult(ToolResultMessage toolResult) {
        return new ToolRenderRequest(
                toolResult == null ? null : toolResult.getToolName(),
                toolResult == null ? null : toolResult.getToolCallId(),
                null,
                Map.of(),
                null,
                toolResult,
                null,
                false,
                false
        );
    }

    public static ToolRenderRequest forPartial(ToolCallContent toolCall, ToolExecutionResult partialResult) {
        return new ToolRenderRequest(
                toolCall == null ? null : toolCall.getToolName(),
                toolCall == null ? null : toolCall.getToolCallId(),
                toolCall == null ? null : toolCall.getArgumentsJson(),
                Map.of(),
                toolCall,
                null,
                partialResult,
                true,
                false
        );
    }
}
