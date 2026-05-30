package io.github.lingjiuu.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultContext;

import java.util.List;
import java.util.Map;

public final class ToolTestSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolTestSupport() {
    }

    public static <I, O> ToolExecutionResult execute(Tool<I, O> tool, ToolInvocation invocation) {
        I input = tool.parseInput(argumentsJson(invocation == null ? null : invocation.getArguments()));
        ToolUseContext context = ToolUseContext.builder()
                .tool(tool)
                .toolCall(invocation == null ? null : invocation.getToolCall())
                .cancellationToken(invocation == null ? ToolCancellationToken.none() : invocation.cancellationToken())
                .deadline(invocation == null ? null : invocation.getDeadline())
                .readFileState(invocation == null ? null : invocation.readFileState())
                .build();
        ToolCallResult<O> callResult = tool.call(input, context);
        if (callResult == null) {
            return ToolExecutionResult.errorText("Tool returned no result.");
        }
        if (callResult.hasFailure()) {
            return ToolExecutionResult.errorText(callResult.failure().message());
        }
        try {
            tool.validateOutput(callResult.output());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.errorText("Tool output did not match outputSchema: " + e.getMessage());
        }
        ToolResultContext<I, O> resultContext = new ToolResultContext<>(
                context.getToolCall(),
                input,
                callResult.status(),
                null
        );
        ModelToolResult model = tool.toModelResult(callResult.output(), resultContext);
        ToolDisplayResult display = tool.toDisplayResult(callResult.output(), resultContext);
        return ToolExecutionResult.builder()
                .contents(model == null ? List.of() : model.contents())
                .details(display == null ? null : display.data())
                .error(callResult.isError() || (model != null && model.error()))
                .build();
    }

    private static String argumentsJson(Map<String, Object> arguments) {
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize tool arguments", e);
        }
    }
}
