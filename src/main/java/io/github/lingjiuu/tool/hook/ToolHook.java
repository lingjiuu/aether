package io.github.lingjiuu.tool.hook;

import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.permission.PermissionDecision;

public interface ToolHook {

    default PermissionDecision beforeToolCall(ToolInvocation invocation, ToolExecutionContext context) {
        return PermissionDecision.allow();
    }

    default ToolExecutionResult afterToolCall(
            ToolInvocation invocation,
            ToolExecutionContext context,
            ToolExecutionResult result
    ) {
        return result;
    }

    default ToolExecutionResult onToolFailure(
            ToolInvocation invocation,
            ToolExecutionContext context,
            RuntimeException error
    ) {
        return null;
    }
}
