package io.github.lingjiuu.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, ToolDefinition> definitionsByName = new LinkedHashMap<>();

    public ToolRegistry() {
    }

    public void register(ToolDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("tool definition must not be null");
        }
        if (definition.name() == null || definition.name().isBlank()) {
            throw new IllegalArgumentException("tool definition name must not be blank");
        }
        definitionsByName.put(definition.name(), definition);
    }

    public int size() {
        return definitionsByName.size();
    }

    public List<ToolDefinition> definitions() {
        return List.copyOf(new ArrayList<>(definitionsByName.values()));
    }

    public ToolDefinition findDefinition(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return definitionsByName.get(name);
    }

    public ToolDefinition requireDefinition(String name) {
        ToolDefinition definition = definitionsByName.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unsupported tool: " + name);
        }
        return definition;
    }
}
