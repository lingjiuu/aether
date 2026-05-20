package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolRiskLevel;

public class PermissionManager {

    public PermissionDecision decide(ToolInvocation invocation, ToolExecutionContext context) {
        if (invocation == null || invocation.definition() == null) {
            return PermissionDecision.deny("Tool is not available.");
        }
        ToolRiskLevel riskLevel = invocation.definition().riskLevel();
        if (riskLevel == null || riskLevel == ToolRiskLevel.UNKNOWN) {
            return PermissionDecision.ask("Tool risk is unknown.");
        }
        if (riskLevel == ToolRiskLevel.READ_ONLY) {
            return PermissionDecision.allow();
        }
        if (riskLevel == ToolRiskLevel.WRITE || riskLevel == ToolRiskLevel.EXEC) {
            return PermissionDecision.ask("Tool can modify state or execute commands.");
        }
        return PermissionDecision.allow();
    }
}
