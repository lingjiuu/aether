package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;

import java.util.Map;

public class ToolExecutor {

    private final ToolArgumentValidator argumentValidator;

    public ToolExecutor() {
        this(new ToolArgumentValidator());
    }

    public ToolExecutor(ToolArgumentValidator argumentValidator) {
        this.argumentValidator = argumentValidator;
    }

    public ToolResultMessage execute(
            ToolDefinition definition,
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            ToolUpdateCallback onUpdate
    ) {
        Map<String, Object> arguments = argumentValidator.validate(definition, toolCall.getArgumentsJson());

        ToolExecutionContext context = ToolExecutionContext.builder()
                .assistantMessage(assistantMessage)
                .toolCall(toolCall)
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .arguments(arguments)
                .build();

        definition.beforeExecute(context);
        if (context.isBlocked()) {
            return toToolResultMessage(toolCall, ToolExecutionResult.errorText(
                    context.getBlockedReason() == null || context.getBlockedReason().isBlank()
                            ? "Tool execution was blocked."
                            : context.getBlockedReason()
            ));
        }

        ToolExecutionResult result = definition.execute(context, onUpdate);

        result = definition.afterExecute(context.toBuilder()
                .result(result)
                .build());

        return toToolResultMessage(toolCall, result);
    }

    private ToolResultMessage toToolResultMessage(ToolCallContent toolCall, ToolExecutionResult result) {
        return ToolResultMessage.builder()
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .details(result.getDetails())
                .isError(result.isError())
                .contents(result.getContents())
                .build();
    }
}
