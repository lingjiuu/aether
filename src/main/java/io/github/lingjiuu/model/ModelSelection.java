package io.github.lingjiuu.model;

import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;

import java.util.Objects;

public record ModelSelection(
        ModelInfo model,
        ProviderEndpoint endpoint,
        ProviderAuth auth,
        ReasoningOptions reasoning
) {

    public boolean sameRuntimeAs(ModelSelection other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        return Objects.equals(providerId(endpoint), providerId(other.endpoint))
                && Objects.equals(modelId(model), modelId(other.model))
                && Objects.equals(reasoningEffortName(), other.reasoningEffortName());
    }

    public Long contextWindowTokens() {
        return model == null ? null : model.getContextWindowTokens();
    }

    public Long autoCompactTokenLimit() {
        return model == null ? null : model.resolvedAutoCompactTokenLimit();
    }

    public String reasoningEffortName() {
        return reasoning == null ? null : reasoning.effortName();
    }

    private static String providerId(ProviderEndpoint endpoint) {
        return endpoint == null ? null : endpoint.providerId();
    }

    private static String modelId(ModelInfo model) {
        return model == null ? null : model.getId();
    }

}
