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
            BiConsumer<Tool, ToolExecutionResult> updateSink
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
            return PreparedToolCall.failed(ToolExecutionResult.errorText(prefix + safeToolName(toolCall)));
        }

        Tool tool = resolution.tool();
        Map<String, Object> arguments;
        try {
            arguments = tool.validateArguments(toolCall.getArgumentsJson());
        } catch (IllegalArgumentException e) {
            return PreparedToolCall.failed(ToolExecutionResult.errorText(
                    "Invalid tool arguments for " + safeToolName(toolCall) + ": " + e.getMessage()
            ));
        }

        Consumer<ToolExecutionResult> invocationUpdateSink = updateSink == null
                ? null
                : partialResult -> updateSink.accept(tool, partialResult);
        ToolInvocation invocation = ToolInvocation.builder()
                .tool(tool)
                .toolCall(toolCall)
                .arguments(arguments == null ? Map.of() : arguments)
                .cancellationToken(cancellationToken == null ? ToolCancellationToken.none() : cancellationToken)
                .deadline(deadline)
                .readFileState(readFileState)
                .updateSink(invocationUpdateSink)
                .build();
        return PreparedToolCall.ready(tool, invocation);
    }

    public ToolExecutionResult dispatch(PreparedToolCall prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared tool call must not be null");
        }
        if (!prepared.ready()) {
            return prepared.failureResult();
        }

        ToolInvocation invocation = prepared.invocation();
        try {
            checkRuntimeBoundary(invocation);
            ToolExecutionResult result = prepared.tool().execute(invocation);
            checkRuntimeBoundary(invocation);
            return result == null ? ToolExecutionResult.errorText("Tool returned no result.") : result;
        } catch (ToolCancelledException e) {
            return ToolExecutionResult.errorText(e.getMessage());
        } catch (ToolTimedOutException e) {
            return ToolExecutionResult.errorText(e.getMessage());
        } catch (RuntimeException e) {
            return ToolExecutionResult.errorText("Tool execution failed: " + e.getMessage());
        }
    }

    private void checkRuntimeBoundary(ToolInvocation invocation) {
        invocation.throwIfCancellationRequested();
        if (invocation.remainingTimeout().isPresent() && invocation.remainingTimeout().get().isZero()) {
            throw new ToolTimedOutException();
        }
    }

    private String safeToolName(ToolCallContent toolCall) {
        return toolCall.getToolName() == null || toolCall.getToolName().isBlank()
                ? "<unknown>"
                : toolCall.getToolName();
    }

    public record PreparedToolCall(
            Tool tool,
            ToolInvocation invocation,
            ToolExecutionResult failureResult
    ) {

        public static PreparedToolCall ready(Tool tool, ToolInvocation invocation) {
            if (tool == null) {
                throw new IllegalArgumentException("tool must not be null");
            }
            if (invocation == null) {
                throw new IllegalArgumentException("invocation must not be null");
            }
            return new PreparedToolCall(tool, invocation, null);
        }

        public static PreparedToolCall failed(ToolExecutionResult failureResult) {
            if (failureResult == null) {
                throw new IllegalArgumentException("failureResult must not be null");
            }
            return new PreparedToolCall(null, null, failureResult);
        }

        public boolean ready() {
            return tool != null && invocation != null;
        }
    }

    private static final class ToolTimedOutException extends RuntimeException {
        private ToolTimedOutException() {
            super("Tool execution timed out.");
        }
    }
}
