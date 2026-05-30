package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.nio.file.Path;
import java.util.Map;

public class PermissionManager {

    private volatile PermissionPreset preset;
    private final WorkspaceAccessPolicy workspaceAccessPolicy;

    public PermissionManager() {
        this(
                PermissionPreset.DEFAULT,
                WorkspaceAccessPolicy.rootedAt(Path.of(System.getProperty("user.dir")))
        );
    }

    public PermissionManager(PermissionPreset preset, WorkspaceAccessPolicy workspaceAccessPolicy) {
        this.preset = preset == null ? PermissionPreset.DEFAULT : preset;
        this.workspaceAccessPolicy = workspaceAccessPolicy == null
                ? WorkspaceAccessPolicy.rootedAt(Path.of(System.getProperty("user.dir")))
                : workspaceAccessPolicy;
    }

    public PermissionPreset preset() {
        return preset;
    }

    public void setPreset(PermissionPreset preset) {
        this.preset = preset == null ? PermissionPreset.DEFAULT : preset;
    }

    public PermissionDecision decide(Tool<?, ?> tool, String toolName, Map<String, Object> arguments) {
        if (tool == null) {
            return PermissionDecision.deny("Tool is not available.");
        }

        PermissionPreset activePreset = preset;
        if (activePreset.permissionProfile() == PermissionProfile.FULL_ACCESS
                || activePreset.approvalPolicy() == ApprovalPolicy.NEVER) {
            return PermissionDecision.allow();
        }

        String resolvedToolName = toolName;
        if (resolvedToolName == null || resolvedToolName.isBlank()) {
            resolvedToolName = tool.name();
        }
        if (resolvedToolName == null || resolvedToolName.isBlank()) {
            return decideByRiskLevel(tool);
        }

        return switch (resolvedToolName) {
            case "Read", "read", "Glob", "glob", "Grep", "grep" -> PermissionDecision.allow();
            case "Write", "write", "Edit", "edit" -> decideWorkspaceWrite(arguments);
            case "Bash", "bash", "PowerShell", "powershell" -> PermissionDecision.ask("Tool can execute commands.");
            default -> decideByRiskLevel(tool);
        };
    }

    private PermissionDecision decideWorkspaceWrite(Map<String, Object> arguments) {
        String path = stringArgument(arguments, "file_path");
        if (path == null) {
            return PermissionDecision.ask("Tool write path is unknown.");
        }
        if (workspaceAccessPolicy.isInsideWorkspace(path)) {
            return PermissionDecision.allow();
        }
        return PermissionDecision.ask("Tool writes outside the workspace.");
    }

    private PermissionDecision decideByRiskLevel(Tool<?, ?> tool) {
        ToolRiskLevel riskLevel = tool.riskLevel();
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

    private String stringArgument(Map<String, Object> arguments, String name) {
        if (arguments == null || name == null) {
            return null;
        }
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            return null;
        }
        return stringValue;
    }
}
