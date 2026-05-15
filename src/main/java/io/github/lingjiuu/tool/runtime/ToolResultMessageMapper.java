package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.util.List;

public class ToolResultMessageMapper {

    public ToolResultMessage toMessage(ToolCallContent toolCall, ToolExecutionResult result) {
        if (toolCall == null) {
            throw new IllegalArgumentException("tool call must not be null");
        }
        ToolExecutionResult safeResult = result == null
                ? ToolExecutionResult.errorText("Tool returned no result.")
                : result;

        return ToolResultMessage.builder()
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .details(safeResult.getDetails())
                .isError(safeResult.isError())
                .contents(safeResult.getContents() == null ? List.of() : safeResult.getContents())
                .build();
    }
}
