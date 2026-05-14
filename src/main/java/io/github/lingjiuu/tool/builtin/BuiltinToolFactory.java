package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;

import java.util.List;

public final class BuiltinToolFactory {

    private static final List<String> READ_ONLY_TOOL_NAMES = List.of("ls", "find", "grep", "read");

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
        // Future coding tools such as bash, write, and edit should be added here.
        return createReadOnlyTools(accessPolicy);
    }

    public static List<String> readOnlyToolNames() {
        return READ_ONLY_TOOL_NAMES;
    }
}
