package io.github.lingjiuu.provider;

import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.LlmRequest;

public interface Provider {

    String name();

    AssistantStream stream(LlmRequest request);
}
