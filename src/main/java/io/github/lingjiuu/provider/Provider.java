package io.github.lingjiuu.provider;

import io.github.lingjiuu.ai.AssistantRequest;
import io.github.lingjiuu.stream.AssistantStream;

public interface Provider {

    String name();

    AssistantStream stream(AssistantRequest request);
}
