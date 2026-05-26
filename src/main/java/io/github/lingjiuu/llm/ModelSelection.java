package io.github.lingjiuu.llm;

import io.github.lingjiuu.provider.RequestAuth;

public record ModelSelection(
        LlmModel model,
        RequestAuth auth,
        ReasoningOptions reasoning
) {
}
