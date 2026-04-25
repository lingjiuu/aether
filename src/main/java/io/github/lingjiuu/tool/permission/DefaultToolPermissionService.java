package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolInvocation;

public class DefaultToolPermissionService implements ToolPermissionService {

    @Override
    public PermissionDecision decide(ToolInvocation invocation, ToolExecutionContext context) {
        return PermissionDecision.allow();
    }
}
