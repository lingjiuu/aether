package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

public class BuiltinToolFactoryTest extends TestCase {

    public void testCreateReadOnlyToolsInDefaultOrder() {
        List<ToolDefinition> tools = BuiltinToolFactory.createReadOnlyTools(FileAccessPolicy.rootedAt(Path.of(".")));

        assertEquals(List.of("ls", "find", "grep", "read"), tools.stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("ls", "find", "grep", "read"), BuiltinToolFactory.readOnlyToolNames());
    }

    public void testCreateAllToolsIncludesCodingToolsInDefaultOrder() {
        List<ToolDefinition> tools = BuiltinToolFactory.createAllTools(FileAccessPolicy.rootedAt(Path.of(".")));

        assertEquals(List.of("ls", "find", "grep", "read", "write", "edit", "bash"), tools.stream().map(ToolDefinition::name).toList());
        assertEquals(List.of("ls", "find", "grep", "read", "write", "edit", "bash"), BuiltinToolFactory.defaultToolNames());
    }
}
