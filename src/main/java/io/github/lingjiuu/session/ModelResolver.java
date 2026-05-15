package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmModel;

public class ModelResolver {

    public LlmModel resolveCliModel(ModelRegistry registry, String provider, String modelReference) {
        if (registry == null) {
            throw new IllegalArgumentException("model registry must not be null");
        }
        if (modelReference == null || modelReference.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }

        String resolvedProvider = provider;
        String resolvedModelId = modelReference;
        int slash = modelReference.indexOf('/');
        if (slash > 0 && slash < modelReference.length() - 1) {
            resolvedProvider = modelReference.substring(0, slash);
            resolvedModelId = modelReference.substring(slash + 1);
        }

        if (resolvedProvider != null && !resolvedProvider.isBlank()) {
            LlmModel model = registry.find(resolvedProvider, resolvedModelId);
            if (model != null) {
                return model;
            }
            return fallbackModelForProvider(registry, resolvedProvider, resolvedModelId);
        }

        String bareModelId = resolvedModelId;
        java.util.List<LlmModel> matches = registry.getAll().stream()
                .filter(model -> model.getId().equals(bareModelId))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Model id \"" + bareModelId + "\" is ambiguous; specify provider/model.");
        }
        throw new IllegalArgumentException("No model configured for " + bareModelId + ".");
    }

    public LlmModel findInitialModel(
            ModelRegistry registry,
            SettingsManager settingsManager,
            String explicitProvider,
            String explicitModel
    ) {
        String provider = blankToNull(explicitProvider);
        String model = blankToNull(explicitModel);
        if (model != null) {
            return resolveCliModel(registry, provider, model);
        }
        if (provider != null) {
            throw new IllegalStateException("No model configured for " + provider + "/.");
        }

        if (settingsManager != null && settingsManager.getDefaultModel() != null) {
            return resolveCliModel(registry, settingsManager.getDefaultProvider(), settingsManager.getDefaultModel());
        }

        throw new IllegalStateException("No default model configured. Set defaultProvider/defaultModel in settings.json.");
    }

    private LlmModel fallbackModelForProvider(ModelRegistry registry, String provider, String modelId) {
        LlmModel providerModel = registry.getAll().stream()
                .filter(model -> model.getProvider().equals(provider))
                .findFirst()
                .orElse(null);
        if (providerModel == null) {
            throw new IllegalArgumentException("No model configured for " + provider + "/" + modelId + ".");
        }
        return LlmModel.builder()
                .provider(providerModel.getProvider())
                .api(providerModel.getApi())
                .id(modelId)
                .name(modelId)
                .baseUrl(providerModel.getBaseUrl())
                .headers(providerModel.getHeaders())
                .input(providerModel.getInput())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
