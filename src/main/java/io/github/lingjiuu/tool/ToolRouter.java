package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.file.ReadFileState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ToolRouter {

    private final ToolRegistry toolRegistry;

    public ToolRouter(ToolRegistry toolRegistry) {
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        this.toolRegistry = toolRegistry;
    }

    public PreparedToolCall buildInvocation(
            ToolCallContent toolCall,
            List<String> activeToolNames,
            ToolCancellationToken cancellationToken,
            Instant deadline,
            ReadFileState readFileState,
            BiConsumer<Tool<?, ?>, io.github.lingjiuu.tool.result.ToolDisplayResult> updateSink
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }

        ToolRegistry.ToolResolution resolution = toolRegistry.resolve(toolCall.getToolName(), activeToolNames);
        if (!resolution.found()) {
            String prefix = switch (resolution.status()) {
                case INACTIVE -> "Inactive tool: ";
                case UNSUPPORTED -> "Unsupported tool: ";
                case FOUND -> throw new IllegalStateException("found tool resolution must include a tool");
            };
            return PreparedToolCall.failed(ToolCallResult.failure(ToolFailure.validation(prefix + safeToolName(toolCall))));
        }

        Tool<?, ?> tool = resolution.tool();
        Object input;
        try {
            input = parseInput(tool, toolCall.getArgumentsJson());
        } catch (IllegalArgumentException e) {
            return PreparedToolCall.failed(ToolCallResult.failure(ToolFailure.schema(
                    "Invalid tool arguments for " + safeToolName(toolCall) + ": " + e.getMessage()
            )));
        }

        Consumer<io.github.lingjiuu.tool.result.ToolDisplayResult> invocationUpdateSink = updateSink == null
                ? null
                : partialResult -> updateSink.accept(tool, partialResult);
        ToolUseContext context = ToolUseContext.builder()
                .tool(tool)
                .toolCall(toolCall)
                .cancellationToken(cancellationToken == null ? ToolCancellationToken.none() : cancellationToken)
                .deadline(deadline)
                .readFileState(readFileState)
                .updateSink(invocationUpdateSink)
                .build();
        return PreparedToolCall.ready(tool, input, permissionArguments(tool, input), context);
    }

    public ToolCallResult<?> dispatch(PreparedToolCall prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared tool call must not be null");
        }
        if (!prepared.ready()) {
            return prepared.failureResult();
        }

        ToolUseContext context = prepared.context();
        try {
            checkRuntimeBoundary(context);
            ToolCallResult<?> result = dispatchTyped(prepared.tool(), prepared.input(), context);
            checkRuntimeBoundary(context);
            return result == null ? ToolCallResult.failure(ToolFailure.runtime("Tool returned no result.")) : result;
        } catch (ToolCancelledException e) {
            return ToolCallResult.failure(ToolFailure.cancellation(e.getMessage()), ToolCallStatus.ABORTED);
        } catch (ToolTimedOutException e) {
            return ToolCallResult.failure(ToolFailure.timeout(e.getMessage()));
        } catch (RuntimeException e) {
            return ToolCallResult.failure(ToolFailure.runtime("Tool execution failed: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private <I, O> I parseInput(Tool<?, ?> tool, String argumentsJson) {
        return ((Tool<I, O>) tool).parseInput(argumentsJson);
    }

    @SuppressWarnings("unchecked")
    private <I, O> Map<String, Object> permissionArguments(Tool<?, ?> tool, Object input) {
        Map<String, Object> arguments = ((Tool<I, O>) tool).permissionArguments((I) input);
        return arguments == null ? Map.of() : arguments;
    }

    @SuppressWarnings("unchecked")
    private <I, O> ToolCallResult<O> dispatchTyped(Tool<?, ?> tool, Object input, ToolUseContext context) {
        Tool<I, O> typedTool = (Tool<I, O>) tool;
        I typedInput = (I) input;
        ValidationResult validation = typedTool.validateInput(typedInput, context);
        if (validation != null && !validation.valid()) {
            return ToolCallResult.failure(ToolFailure.validation(validation.message()));
        }
        ToolCallResult<O> result = typedTool.call(typedInput, context);
        if (result != null && !result.hasFailure()) {
            try {
                typedTool.validateOutput(result.output());
            } catch (IllegalArgumentException e) {
                return ToolCallResult.failure(ToolFailure.schema(
                        "Tool output did not match outputSchema for " + safeToolName(context.getToolCall()) + ": " + e.getMessage()
                ));
            }
        }
        return result;
    }

    private void checkRuntimeBoundary(ToolUseContext context) {
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

    public record PreparedToolCall(
            Tool<?, ?> tool,
            Object input,
            Map<String, Object> permissionArguments,
            ToolUseContext context,
            ToolCallResult<?> failureResult
    ) {

        public static PreparedToolCall ready(
                Tool<?, ?> tool,
                Object input,
                Map<String, Object> permissionArguments,
                ToolUseContext context
        ) {
            if (tool == null) {
                throw new IllegalArgumentException("tool must not be null");
            }
            if (context == null) {
                throw new IllegalArgumentException("context must not be null");
            }
            return new PreparedToolCall(tool, input, permissionArguments == null ? Map.of() : Map.copyOf(permissionArguments), context, null);
        }

        public static PreparedToolCall failed(ToolCallResult<?> failureResult) {
            if (failureResult == null) {
                throw new IllegalArgumentException("failureResult must not be null");
            }
            return new PreparedToolCall(null, null, Map.of(), null, failureResult);
        }

        public boolean ready() {
            return tool != null && context != null;
        }
    }

    private static final class ToolTimedOutException extends RuntimeException {
        private ToolTimedOutException() {
            super("Tool execution timed out.");
        }
    }
}
