package io.github.lingjiuu.instructions;

import java.nio.file.Path;
import java.util.List;

public record AgentsMdInstructions(
        String text,
        List<Path> sources
) {

    public AgentsMdInstructions {
        text = text == null ? "" : text.trim();
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static AgentsMdInstructions empty() {
        return new AgentsMdInstructions("", List.of());
    }

    public boolean isEmpty() {
        return text.isBlank();
    }
}
