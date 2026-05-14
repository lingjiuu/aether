package io.github.lingjiuu.tool;

import java.util.ArrayList;
import java.util.List;

public final class ToolPoolSnapshot {

    private final ActiveToolSet activeToolSet;

    public ToolPoolSnapshot(List<ToolVisibility> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        List<ToolActivation> activations = new ArrayList<>();
        List<ToolDefinition> visibleTools = new ArrayList<>();
        for (ToolVisibility entry : entries) {
            if (entry.visible()) {
                activations.add(ToolActivation.active(entry.definition()));
                visibleTools.add(entry.definition());
            } else {
                activations.add(ToolActivation.inactive(entry.definition(), entry.reason()));
            }
        }
        this.activeToolSet = ActiveToolSet.of(activations, visibleTools);
    }

    public ToolPoolSnapshot(ActiveToolSet activeToolSet) {
        if (activeToolSet == null) {
            throw new IllegalArgumentException("activeToolSet must not be null");
        }
        this.activeToolSet = activeToolSet;
    }

    public static ToolPoolSnapshot ofVisibleTools(List<ToolDefinition> definitions) {
        return new ToolPoolSnapshot(ActiveToolSet.ofActiveTools(definitions));
    }

    public List<ToolVisibility> entries() {
        List<ToolVisibility> entries = new ArrayList<>();
        for (ToolActivation activation : activeToolSet.entries()) {
            entries.add(ToolVisibility.fromActivation(activation));
        }
        return List.copyOf(entries);
    }

    public List<ToolDefinition> visibleTools() {
        return activeToolSet.activeTools();
    }

    public ToolDefinition findVisible(String name) {
        return activeToolSet.findActive(name);
    }
}
