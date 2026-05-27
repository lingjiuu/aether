package io.github.lingjiuu.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ToolRegistry {

    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

    public ToolRegistry() {
    }

    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (toolsByName.containsKey(tool.name())) {
            throw new IllegalArgumentException("Tool already registered: " + tool.name());
        }
        toolsByName.put(tool.name(), tool);
    }

    public int size() {
        return toolsByName.size();
    }

    public List<Tool> tools() {
        return List.copyOf(new ArrayList<>(toolsByName.values()));
    }

    public Tool findTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return toolsByName.get(name);
    }

    public List<Tool> activeTools(List<String> activeToolNames) {
        if (activeToolNames == null) {
            return tools();
        }

        Set<String> requestedNames = requestedToolNames(activeToolNames);

        List<Tool> activeTools = new ArrayList<>();
        for (String name : requestedNames) {
            Tool tool = findTool(name);
            if (tool != null) {
                activeTools.add(tool);
            }
        }
        return List.copyOf(activeTools);
    }

    public ToolResolution resolve(String name, List<String> activeToolNames) {
        if (name == null || name.isBlank()) {
            return ToolResolution.unsupported(name);
        }

        Tool tool = findTool(name);
        if (tool == null) {
            return ToolResolution.unsupported(name);
        }

        if (activeToolNames != null && !requestedToolNames(activeToolNames).contains(name)) {
            return ToolResolution.inactive(name);
        }

        return ToolResolution.found(tool);
    }

    private Set<String> requestedToolNames(List<String> activeToolNames) {
        Set<String> requestedNames = new LinkedHashSet<>();
        for (String name : activeToolNames) {
            if (name != null && !name.isBlank()) {
                requestedNames.add(name);
            }
        }
        return requestedNames;
    }

    public record ToolResolution(
            ToolResolutionStatus status,
            String name,
            Tool tool
    ) {

        public static ToolResolution found(Tool tool) {
            if (tool == null) {
                throw new IllegalArgumentException("tool must not be null");
            }
            return new ToolResolution(ToolResolutionStatus.FOUND, tool.name(), tool);
        }

        public static ToolResolution inactive(String name) {
            return new ToolResolution(ToolResolutionStatus.INACTIVE, name, null);
        }

        public static ToolResolution unsupported(String name) {
            return new ToolResolution(ToolResolutionStatus.UNSUPPORTED, name, null);
        }

        public boolean found() {
            return status == ToolResolutionStatus.FOUND && tool != null;
        }
    }

    public enum ToolResolutionStatus {
        FOUND,
        INACTIVE,
        UNSUPPORTED
    }
}
