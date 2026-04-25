package io.github.lingjiuu.tool;

import java.util.Objects;

public class ToolPoolCompiler {

    public ToolPoolSnapshot compile(ToolRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("tool registry must not be null");
        }
        return ToolPoolSnapshot.ofVisibleTools(registry.definitions().stream()
                .filter(Objects::nonNull)
                .toList());
    }
}
