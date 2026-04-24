package io.github.lingjiuu.provider.protocol;

import java.util.List;

public record NormalizedUserMessage(List<NormalizedContent> contents) implements NormalizedRequestMessage {

    public NormalizedUserMessage {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    @Override
    public Kind kind() {
        return Kind.USER;
    }

    public NormalizedUserMessage withContents(List<NormalizedContent> newContents) {
        return new NormalizedUserMessage(newContents);
    }
}
