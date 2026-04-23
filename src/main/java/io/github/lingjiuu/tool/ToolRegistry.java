package io.github.lingjiuu.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, ToolDefinition> definitionsByName = new LinkedHashMap<>();
    private final ToolExecutor toolExecutor;

    public ToolRegistry() {
        this(new ToolExecutor());
    }

    public ToolRegistry(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
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

    public io.github.lingjiuu.message.ToolResultMessage execute(
            io.github.lingjiuu.message.AssistantMessage assistantMessage,
            io.github.lingjiuu.message.content.ToolCallContent toolCall,
            ToolUpdateCallback onUpdate
    ) {
        return toolExecutor.execute(requireDefinition(toolCall.getToolName()), assistantMessage, toolCall, onUpdate);
    }

    public ToolDefinition requireDefinition(String name) {
        ToolDefinition definition = definitionsByName.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unsupported tool: " + name);
        }
        return definition;
    }
}
