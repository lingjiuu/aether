package io.github.lingjiuu.tool;

import java.util.ArrayList;
import java.util.List;

public final class ToolPoolSnapshot {

    private final List<ToolVisibility> entries;

    public ToolPoolSnapshot(List<ToolVisibility> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        this.entries = List.copyOf(entries);
    }

    public static ToolPoolSnapshot ofVisibleTools(List<ToolDefinition> definitions) {
        if (definitions == null) {
            return new ToolPoolSnapshot(List.of());
        }
        List<ToolVisibility> entries = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            if (definition != null) {
                entries.add(ToolVisibility.visible(definition));
            }
        }
        return new ToolPoolSnapshot(entries);
    }

    public List<ToolVisibility> entries() {
        return entries;
    }

    public List<ToolDefinition> visibleTools() {
        List<ToolDefinition> visibleTools = new ArrayList<>();
        for (ToolVisibility entry : entries) {
            if (entry.visible()) {
                visibleTools.add(entry.definition());
            }
        }
        return List.copyOf(visibleTools);
    }

    public ToolDefinition findVisible(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ToolVisibility entry : entries) {
            if (entry.visible() && name.equals(entry.definition().name())) {
                return entry.definition();
            }
        }
        return null;
    }
}
