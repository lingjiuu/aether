package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

public final class ActiveToolSet {

    private final List<ToolActivation> entries;
    private final List<ToolDefinition> activeTools;

    private ActiveToolSet(List<ToolActivation> entries, List<ToolDefinition> activeTools) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (activeTools == null) {
            throw new IllegalArgumentException("activeTools must not be null");
        }
        this.entries = List.copyOf(entries);
        this.activeTools = List.copyOf(activeTools);
    }

    public static ActiveToolSet of(List<ToolActivation> entries, List<ToolDefinition> activeTools) {
        return new ActiveToolSet(entries, activeTools);
    }

    public static ActiveToolSet ofActiveTools(List<ToolDefinition> definitions) {
        if (definitions == null) {
            return new ActiveToolSet(List.of(), List.of());
        }

        List<ToolActivation> entries = new ArrayList<>();
        List<ToolDefinition> activeTools = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            entries.add(ToolActivation.active(definition));
            activeTools.add(definition);
        }
        return new ActiveToolSet(entries, activeTools);
    }

    public List<ToolActivation> entries() {
        return entries;
    }

    public List<ToolDefinition> activeTools() {
        return activeTools;
    }

    public List<String> activeToolNames() {
        List<String> names = new ArrayList<>();
        for (ToolDefinition definition : activeTools) {
            names.add(definition.name());
        }
        return List.copyOf(names);
    }

    public ToolDefinition findActive(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ToolDefinition definition : activeTools) {
            if (name.equals(definition.name())) {
                return definition;
            }
        }
        return null;
    }

    public ToolDefinition findRegistered(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ToolActivation entry : entries) {
            if (name.equals(entry.definition().name())) {
                return entry.definition();
            }
        }
        return null;
    }
}
