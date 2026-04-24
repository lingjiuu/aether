package io.github.lingjiuu.provider.protocol;

import io.github.lingjiuu.provider.ProviderReplayData;

import java.util.List;

public record NormalizedAssistantMessage(
        List<NormalizedContent> contents,
        ProviderReplayData providerState
) implements NormalizedRequestMessage {

    public NormalizedAssistantMessage {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    @Override
    public Kind kind() {
        return Kind.ASSISTANT;
    }

    public boolean hasProviderReplayData() {
        return providerState != null;
    }

    public NormalizedAssistantMessage withContents(List<NormalizedContent> newContents) {
        return new NormalizedAssistantMessage(newContents, providerState);
    }
}
