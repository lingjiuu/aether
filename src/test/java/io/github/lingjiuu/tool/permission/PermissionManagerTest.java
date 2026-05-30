package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class PermissionManagerTest extends TestCase {

    public void testDefaultAllowsReadOutsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        Path outside = Files.createTempFile("aether-permission-outside", ".txt");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = decide(manager, tool("read", ToolRiskLevel.READ_ONLY), Map.of(
                "path", outside.toString()
        ));

        assertEquals(PermissionDecision.Action.ALLOW, decision.action());
    }

    public void testDefaultAllowsWriteInsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = decide(manager, tool("write", ToolRiskLevel.WRITE), Map.of(
                "file_path", "notes/todo.md"
        ));

        assertEquals(PermissionDecision.Action.ALLOW, decision.action());
    }

    public void testDefaultAsksForWriteOutsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        Path outside = Files.createTempDirectory("aether-permission-outside").resolve("todo.md");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = decide(manager, tool("write", ToolRiskLevel.WRITE), Map.of(
                "file_path", outside.toString()
        ));

        assertEquals(PermissionDecision.Action.ASK, decision.action());
    }

    public void testDefaultAllowsEditInsideWorkspaceWithFilePathArgument() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = decide(manager, tool("edit", ToolRiskLevel.WRITE), Map.of(
                "file_path", "notes/todo.md"
        ));

        assertEquals(PermissionDecision.Action.ALLOW, decision.action());
    }

    public void testDefaultAsksForBash() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = decide(manager, tool("bash", ToolRiskLevel.EXEC), Map.of(
                "command", "pwd"
        ));

        assertEquals(PermissionDecision.Action.ASK, decision.action());
    }

    public void testDefaultAsksForPowerShell() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = decide(manager, tool("powershell", ToolRiskLevel.EXEC), Map.of(
                "command", "Get-ChildItem"
        ));

        assertEquals(PermissionDecision.Action.ASK, decision.action());
    }

    public void testFullAccessAllowsEverything() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        Path outside = Files.createTempDirectory("aether-permission-outside").resolve("todo.md");
        PermissionManager manager = new PermissionManager(
                PermissionPreset.FULL_ACCESS,
                WorkspaceAccessPolicy.rootedAt(workspace)
        );

        PermissionDecision write = decide(manager, tool("write", ToolRiskLevel.WRITE), Map.of(
                "file_path", outside.toString()
        ));
        PermissionDecision bash = decide(manager, tool("bash", ToolRiskLevel.EXEC), Map.of(
                "command", "rm -rf build"
        ));
        PermissionDecision unknown = decide(manager, tool("mystery", ToolRiskLevel.UNKNOWN), Map.of());

        assertEquals(PermissionDecision.Action.ALLOW, write.action());
        assertEquals(PermissionDecision.Action.ALLOW, bash.action());
        assertEquals(PermissionDecision.Action.ALLOW, unknown.action());
    }

    public void testPresetCanChangeAtRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision before = decide(manager, tool("bash", ToolRiskLevel.EXEC), Map.of(
                "command", "pwd"
        ));
        manager.setPreset(PermissionPreset.FULL_ACCESS);
        PermissionDecision after = decide(manager, tool("bash", ToolRiskLevel.EXEC), Map.of(
                "command", "pwd"
        ));

        assertEquals(PermissionPreset.FULL_ACCESS, manager.preset());
        assertEquals(PermissionDecision.Action.ASK, before.action());
        assertEquals(PermissionDecision.Action.ALLOW, after.action());
    }

    private PermissionManager defaultManager(Path workspace) {
        return new PermissionManager(PermissionPreset.DEFAULT, WorkspaceAccessPolicy.rootedAt(workspace));
    }

    private PermissionDecision decide(PermissionManager manager, Tool tool, Map<String, Object> arguments) {
        return manager.decide(tool, tool.name(), arguments);
    }

    private Tool tool(String name, ToolRiskLevel riskLevel) {
        return new Tool<Object, String>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String label() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of();
            }

            @Override
            public ToolRiskLevel riskLevel() {
                return riskLevel;
            }

            @Override
            public Object parseInput(String argumentsJson) {
                return new Object();
            }

            @Override
            public ToolCallResult<String> call(Object input, ToolUseContext context) {
                return ToolCallResult.success("ok");
            }

            @Override
            public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
                return ModelToolResult.text(output);
            }
        };
    }
}
