package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.validation.ToolSchemaValidator;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Consumer;

public class ToolRunner {

    private final ToolRegistry toolRegistry;
    private final ToolSchemaValidator argumentValidator;

    public ToolRunner(ToolRegistry toolRegistry) {
        this(toolRegistry, new ToolSchemaValidator());
    }

    public ToolRunner(ToolRegistry toolRegistry, ToolSchemaValidator argumentValidator) {
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        if (argumentValidator == null) {
            throw new IllegalArgumentException("argumentValidator must not be null");
        }
        this.toolRegistry = toolRegistry;
        this.argumentValidator = argumentValidator;
    }

    public ToolRunResult prepare(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            List<String> activeToolNames,
            ToolCancellationToken cancellationToken,
            Instant deadline,
            Consumer<ToolExecutionResult> updateSink
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }

        ToolDefinition definition = toolRegistry.findActiveDefinition(toolCall.getToolName(), activeToolNames);
        if (definition == null) {
            String prefix = toolRegistry.isRegistered(toolCall.getToolName()) ? "Inactive tool: " : "Unsupported tool: ";
            return ToolRunResult.failed(ToolExecutionResult.errorText(prefix + safeToolName(toolCall)));
        }

        Map<String, Object> arguments;
        try {
            arguments = argumentValidator.validate(definition, toolCall.getArgumentsJson());
        } catch (IllegalArgumentException e) {
            return ToolRunResult.failed(ToolExecutionResult.errorText(
                    "Invalid tool arguments for " + safeToolName(toolCall) + ": " + e.getMessage()
            ));
        }

        ToolExecutionContext context = buildContext(
                assistantMessage,
                toolCall,
                arguments,
                cancellationToken,
                deadline,
                updateSink
        );
        return ToolRunResult.ready(definition, ToolInvocation.of(assistantMessage, toolCall, definition), context);
    }

    public ToolExecutionResult run(ToolRunResult prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared tool run must not be null");
        }
        if (!prepared.ready()) {
            return prepared.failureResult();
        }

        ToolExecutionContext context = prepared.context();
        try {
            checkRuntimeBoundary(context);
            ToolExecutionResult result = prepared.definition().execute(context);
            checkRuntimeBoundary(context);
            return result == null ? ToolExecutionResult.errorText("Tool returned no result.") : result;
        } catch (ToolCancelledException e) {
            return ToolExecutionResult.errorText(e.getMessage());
        } catch (ToolTimedOutException e) {
            return ToolExecutionResult.errorText(e.getMessage());
        } catch (RuntimeException e) {
            return ToolExecutionResult.errorText("Tool execution failed: " + e.getMessage());
        }
    }

    public ToolExecutionResult run(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            List<String> activeToolNames,
            ToolCancellationToken cancellationToken,
            Instant deadline,
            Consumer<ToolExecutionResult> updateSink
    ) {
        return run(prepare(assistantMessage, toolCall, activeToolNames, cancellationToken, deadline, updateSink));
    }

    private ToolExecutionContext buildContext(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            Map<String, Object> arguments,
            ToolCancellationToken cancellationToken,
            Instant deadline,
            Consumer<ToolExecutionResult> updateSink
    ) {
        return ToolExecutionContext.builder()
                .assistantMessage(assistantMessage)
                .toolCall(toolCall)
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .arguments(arguments == null ? Map.of() : arguments)
                .cancellationToken(cancellationToken == null ? ToolCancellationToken.none() : cancellationToken)
                .deadline(deadline)
                .updateSink(updateSink)
                .build();
    }

    private void checkRuntimeBoundary(ToolExecutionContext context) {
        context.throwIfCancellationRequested();
        if (context.remainingTimeout().isPresent() && context.remainingTimeout().get().isZero()) {
            throw new ToolTimedOutException();
        }
    }

    private String safeToolName(ToolCallContent toolCall) {
        return toolCall.getToolName() == null || toolCall.getToolName().isBlank()
                ? "<unknown>"
                : toolCall.getToolName();
    }

    private static final class ToolTimedOutException extends RuntimeException {
        private ToolTimedOutException() {
            super("Tool execution timed out.");
        }
    }
}
