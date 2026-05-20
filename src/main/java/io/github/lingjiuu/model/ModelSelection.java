package io.github.lingjiuu.model;

import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.provider.RequestAuth;

public record ModelSelection(
        LlmModel model,
        RequestAuth auth,
        ReasoningOptions reasoning
) {
}
