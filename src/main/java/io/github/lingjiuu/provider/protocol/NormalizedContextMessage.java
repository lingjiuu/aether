package io.github.lingjiuu.provider.protocol;

import java.util.List;

public record NormalizedContextMessage(List<NormalizedContent> contents) implements NormalizedRequestMessage {

    public NormalizedContextMessage {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    @Override
    public Kind kind() {
        return Kind.CONTEXT;
    }

    public NormalizedContextMessage withContents(List<NormalizedContent> newContents) {
        return new NormalizedContextMessage(newContents);
    }
}
