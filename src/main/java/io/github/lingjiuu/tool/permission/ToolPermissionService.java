package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolInvocation;

public interface ToolPermissionService {

    PermissionDecision decide(ToolInvocation invocation, ToolExecutionContext context);
}
