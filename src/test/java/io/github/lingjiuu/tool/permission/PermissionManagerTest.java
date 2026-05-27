package io.github.lingjiuu.tool.permission;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolRiskLevel;
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

        PermissionDecision decision = manager.decide(invocation(tool("read", ToolRiskLevel.READ_ONLY), Map.of(
                "path", outside.toString()
        )));

        assertEquals(PermissionDecision.Action.ALLOW, decision.action());
    }

    public void testDefaultAllowsWriteInsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = manager.decide(invocation(tool("write", ToolRiskLevel.WRITE), Map.of(
                "file_path", "notes/todo.md"
        )));

        assertEquals(PermissionDecision.Action.ALLOW, decision.action());
    }

    public void testDefaultAsksForWriteOutsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        Path outside = Files.createTempDirectory("aether-permission-outside").resolve("todo.md");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = manager.decide(invocation(tool("write", ToolRiskLevel.WRITE), Map.of(
                "file_path", outside.toString()
        )));

        assertEquals(PermissionDecision.Action.ASK, decision.action());
    }

    public void testDefaultAllowsEditInsideWorkspaceWithFilePathArgument() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = manager.decide(invocation(tool("edit", ToolRiskLevel.WRITE), Map.of(
                "file_path", "notes/todo.md"
        )));

        assertEquals(PermissionDecision.Action.ALLOW, decision.action());
    }

    public void testDefaultAsksForBash() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        PermissionManager manager = defaultManager(workspace);

        PermissionDecision decision = manager.decide(invocation(tool("bash", ToolRiskLevel.EXEC), Map.of(
                "command", "ls"
        )));

        assertEquals(PermissionDecision.Action.ASK, decision.action());
    }

    public void testFullAccessAllowsEverything() throws Exception {
        Path workspace = Files.createTempDirectory("aether-permission-workspace");
        Path outside = Files.createTempDirectory("aether-permission-outside").resolve("todo.md");
        PermissionManager manager = new PermissionManager(
                PermissionPreset.FULL_ACCESS,
                WorkspaceAccessPolicy.rootedAt(workspace)
        );

        PermissionDecision write = manager.decide(invocation(tool("write", ToolRiskLevel.WRITE), Map.of(
                "file_path", outside.toString()
        )));
        PermissionDecision bash = manager.decide(invocation(tool("bash", ToolRiskLevel.EXEC), Map.of(
                "command", "rm -rf build"
        )));
        PermissionDecision unknown = manager.decide(invocation(tool("mystery", ToolRiskLevel.UNKNOWN), Map.of()));

        assertEquals(PermissionDecision.Action.ALLOW, write.action());
        assertEquals(PermissionDecision.Action.ALLOW, bash.action());
        assertEquals(PermissionDecision.Action.ALLOW, unknown.action());
    }

    private PermissionManager defaultManager(Path workspace) {
        return new PermissionManager(PermissionPreset.DEFAULT, WorkspaceAccessPolicy.rootedAt(workspace));
    }

    private ToolInvocation invocation(Tool tool, Map<String, Object> arguments) {
        return ToolInvocation.builder()
                .tool(tool)
                .arguments(arguments)
                .build();
    }

    private Tool tool(String name, ToolRiskLevel riskLevel) {
        return new Tool() {
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
            public Map<String, Object> parametersSchema() {
                return Map.of();
            }

            @Override
            public ToolRiskLevel riskLevel() {
                return riskLevel;
            }

            @Override
            public ToolExecutionResult execute(ToolInvocation context) {
                return ToolExecutionResult.text("ok");
            }
        };
    }
}
