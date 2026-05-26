package io.github.lingjiuu.model;

import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;

public record ModelSelection(
        ModelInfo model,
        ProviderEndpoint endpoint,
        ProviderAuth auth,
        ReasoningOptions reasoning
) {
}
