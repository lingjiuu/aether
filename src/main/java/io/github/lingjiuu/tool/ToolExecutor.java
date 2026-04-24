package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;

import java.util.Map;

public class ToolExecutor {

    private final ToolArgumentValidator argumentValidator;
    private final ToolResultMapper resultMapper;

    public ToolExecutor() {
        this(new ToolArgumentValidator(), new ToolResultMapper());
    }

    public ToolExecutor(ToolArgumentValidator argumentValidator) {
        this(argumentValidator, new ToolResultMapper());
    }

    public ToolExecutor(ToolArgumentValidator argumentValidator, ToolResultMapper resultMapper) {
        if (argumentValidator == null) {
            throw new IllegalArgumentException("argumentValidator must not be null");
        }
        if (resultMapper == null) {
            throw new IllegalArgumentException("resultMapper must not be null");
        }
        this.argumentValidator = argumentValidator;
        this.resultMapper = resultMapper;
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
            return resultMapper.toMessage(toolCall, ToolExecutionResult.errorText(
                    context.getBlockedReason() == null || context.getBlockedReason().isBlank()
                            ? "Tool execution was blocked."
                            : context.getBlockedReason()
            ));
        }

        ToolExecutionResult result = definition.execute(context, onUpdate);

        result = definition.afterExecute(context.toBuilder()
                .result(result)
                .build());

        return resultMapper.toMessage(toolCall, result);
    }
}
