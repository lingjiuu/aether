package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

public class DefaultToolsTest extends TestCase {

    public void testCreateReadOnlyToolsInDefaultOrder() {
        List<ToolDefinition> tools = DefaultTools.createReadOnly(WorkspaceAccessPolicy.rootedAt(Path.of(".")));

        assertEquals(List.of("ls", "find", "grep", "read"), tools.stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("ls", "find", "grep", "read"), DefaultTools.readOnlyNames());
    }

    public void testCreateAllToolsIncludesCodingToolsInDefaultOrder() {
        List<ToolDefinition> tools = DefaultTools.createAll(WorkspaceAccessPolicy.rootedAt(Path.of(".")));

        assertEquals(List.of("ls", "find", "grep", "read", "write", "edit", "bash"), tools.stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("ls", "find", "grep", "read", "write", "edit", "bash"), DefaultTools.defaultNames());
    }
}
