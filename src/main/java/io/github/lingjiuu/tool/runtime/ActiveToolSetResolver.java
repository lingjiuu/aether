package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ActiveToolSetResolver {

    public ActiveToolSet compile(ToolRegistry registry, List<String> activeToolNames) {
        if (registry == null) {
            throw new IllegalArgumentException("tool registry must not be null");
        }

        List<ToolDefinition> definitions = registry.definitions();
        if (activeToolNames == null) {
            return ActiveToolSet.ofActiveTools(definitions);
        }

        Set<String> requestedNames = normalizeNames(activeToolNames);
        List<ToolDefinition> activeTools = new ArrayList<>();
        for (String name : requestedNames) {
            ToolDefinition definition = registry.findDefinition(name);
            if (definition != null) {
                activeTools.add(definition);
            }
        }

        List<ToolActivation> entries = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            if (requestedNames.contains(definition.name())) {
                entries.add(ToolActivation.active(definition));
            } else {
                entries.add(ToolActivation.inactive(definition, "Not active for this session."));
            }
        }

        return ActiveToolSet.of(entries, activeTools);
    }

    private Set<String> normalizeNames(List<String> activeToolNames) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : activeToolNames) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
