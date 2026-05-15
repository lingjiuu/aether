package io.github.lingjiuu.tool.hook;

import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.runtime.ToolInvocation;
import io.github.lingjiuu.tool.permission.PermissionDecision;

import java.util.List;

public class ToolHookChain {

    private final List<ToolHook> hooks;

    public ToolHookChain() {
        this(List.of());
    }

    public ToolHookChain(List<ToolHook> hooks) {
        this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
    }

    public static ToolHookChain empty() {
        return new ToolHookChain();
    }

    public PermissionDecision beforeToolCall(ToolInvocation invocation, ToolExecutionContext context) {
        for (ToolHook hook : hooks) {
            PermissionDecision decision = hook.beforeToolCall(invocation, context);
            if (decision != null && !decision.allowed()) {
                return decision;
            }
        }
        return PermissionDecision.allow();
    }

    public ToolExecutionResult afterToolCall(
            ToolInvocation invocation,
            ToolExecutionContext context,
            ToolExecutionResult result
    ) {
        ToolExecutionResult current = result;
        for (ToolHook hook : hooks) {
            ToolExecutionResult next = hook.afterToolCall(invocation, context, current);
            if (next != null) {
                current = next;
            }
        }
        return current;
    }

    public ToolExecutionResult onToolFailure(
            ToolInvocation invocation,
            ToolExecutionContext context,
            RuntimeException error
    ) {
        for (ToolHook hook : hooks) {
            ToolExecutionResult result = hook.onToolFailure(invocation, context, error);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
