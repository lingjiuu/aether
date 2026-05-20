package io.github.lingjiuu.tool;

public record ToolRunResult(
        ToolDefinition definition,
        ToolInvocation invocation,
        ToolExecutionContext context,
        ToolExecutionResult failureResult
) {

    public static ToolRunResult ready(
            ToolDefinition definition,
            ToolInvocation invocation,
            ToolExecutionContext context
    ) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (invocation == null) {
            throw new IllegalArgumentException("invocation must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new ToolRunResult(definition, invocation, context, null);
    }

    public static ToolRunResult failed(ToolExecutionResult failureResult) {
        if (failureResult == null) {
            throw new IllegalArgumentException("failureResult must not be null");
        }
        return new ToolRunResult(null, null, null, failureResult);
    }

    public boolean ready() {
        return definition != null && invocation != null && context != null;
    }
}
