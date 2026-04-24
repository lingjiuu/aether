package io.github.lingjiuu.tool;

import java.util.List;
import java.util.Objects;

public class ToolPoolCompiler {

    public List<ToolDefinition> compile(ToolRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("tool registry must not be null");
        }
        return registry.definitions().stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
