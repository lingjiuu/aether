package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.util.List;

public final class DefaultTools {

    private static final List<String> READ_ONLY_TOOL_NAMES = List.of("ls", "find", "grep", "read");
    private static final List<String> DEFAULT_TOOL_NAMES = List.of("ls", "find", "grep", "read", "write", "edit", "bash");

    private DefaultTools() {
    }

    public static List<ToolDefinition> createReadOnly(WorkspaceAccessPolicy accessPolicy) {
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

    public static List<ToolDefinition> createAll(WorkspaceAccessPolicy accessPolicy) {
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

    public static List<String> readOnlyNames() {
        return READ_ONLY_TOOL_NAMES;
    }

    public static List<String> defaultNames() {
        return DEFAULT_TOOL_NAMES;
    }
}
