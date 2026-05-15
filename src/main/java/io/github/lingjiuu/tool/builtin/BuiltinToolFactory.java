package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;

import java.util.List;

public final class BuiltinToolFactory {

    private static final List<String> READ_ONLY_TOOL_NAMES = List.of("ls", "find", "grep", "read");
    private static final List<String> DEFAULT_TOOL_NAMES = List.of("ls", "find", "grep", "read", "write", "edit", "bash");

    private BuiltinToolFactory() {
    }

    public static List<ToolDefinition> createReadOnlyTools(FileAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        return List.of(
                new LsTool(accessPolicy),
                new FindTool(accessPolicy),
                new GrepTool(accessPolicy),
                new ReadTool(accessPolicy)
        );
    }

    public static List<ToolDefinition> createAllTools(FileAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        return List.of(
                new LsTool(accessPolicy),
                new FindTool(accessPolicy),
                new GrepTool(accessPolicy),
                new ReadTool(accessPolicy),
                new WriteTool(accessPolicy),
                new EditTool(accessPolicy),
                new BashTool(accessPolicy)
        );
    }

    public static List<String> readOnlyToolNames() {
        return READ_ONLY_TOOL_NAMES;
    }

    public static List<String> defaultToolNames() {
        return DEFAULT_TOOL_NAMES;
    }
}
