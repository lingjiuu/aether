package io.github.lingjiuu.provider;

import io.github.lingjiuu.ai.AiModel;

public interface Provider {

    String name();

    AssistantMessageEventStream streamSimple(AiModel model, ProviderContext context, ProviderOptions options);
}
