package io.github.lingjiuu.provider;

import io.github.lingjiuu.llm.LlmModel;

public interface Provider {

    String name();

    ProviderSession openSession(LlmModel model, RequestAuth auth);
}
