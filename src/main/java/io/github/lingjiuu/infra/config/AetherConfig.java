package io.github.lingjiuu.infra.config;

import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ModelOption;
import io.github.lingjiuu.llm.ModelSelection;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.provider.RequestAuth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AetherConfig(
        String defaultProvider,
        String defaultModel,
        String defaultThinkingLevel,
        Map<String, ModelProviderConfig> modelProviders,
        ModelSelection modelSelection,
        List<ModelOption> modelOptions
) {

    public AetherConfig(
            String defaultProvider,
            String defaultModel,
            String defaultThinkingLevel,
            Map<String, ModelProviderConfig> modelProviders
    ) {
        this(defaultProvider, defaultModel, defaultThinkingLevel, modelProviders, null, null);
    }

    public static AetherConfig selected(
            String defaultProvider,
            String defaultModel,
            String defaultThinkingLevel,
            Map<String, ModelProviderConfig> modelProviders,
            String selectedProvider,
            String selectedModel,
            String selectedReasoningEffort
    ) {
        Map<String, ModelProviderConfig> safeModelProviders = copyProviders(modelProviders);
        return new AetherConfig(
                defaultProvider,
                defaultModel,
                defaultThinkingLevel,
                safeModelProviders,
                selectModel(
                        defaultProvider,
                        defaultModel,
                        defaultThinkingLevel,
                        safeModelProviders,
                        selectedProvider,
                        selectedModel,
                        selectedReasoningEffort
                ),
                buildModelOptions(safeModelProviders)
        );
    }

    public AetherConfig {
        modelProviders = copyProviders(modelProviders);
        modelOptions = modelOptions == null ? buildModelOptions(modelProviders) : List.copyOf(modelOptions);
        modelSelection = modelSelection == null
                ? selectModel(defaultProvider, defaultModel, defaultThinkingLevel, modelProviders, null, null, null)
                : modelSelection;
    }

    public ModelSelection selectModel(String explicitProvider, String explicitModel, String explicitReasoningEffort) {
        return selectModel(
                defaultProvider,
                defaultModel,
                defaultThinkingLevel,
                modelProviders,
                explicitProvider,
                explicitModel,
                explicitReasoningEffort
        );
    }

    public record ModelProviderConfig(
            String name,
            String api,
            String baseUrl,
            String apiKey,
            Boolean authHeader,
            Map<String, String> headers,
            List<ModelDefinition> models
    ) {

        public ModelProviderConfig {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    public record ModelDefinition(
            String id,
            String name,
            String api,
            String baseUrl,
            Long contextWindowTokens,
            Long autoCompactTokenLimit,
            Map<String, String> headers,
            List<String> input
    ) {

        public ModelDefinition {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            input = input == null || input.isEmpty() ? List.of("text") : List.copyOf(input);
        }
    }

    private static ModelSelection selectModel(
            String defaultProvider,
            String defaultModel,
            String defaultThinkingLevel,
            Map<String, ModelProviderConfig> modelProviders,
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        String providerId = blankToNull(explicitProvider);
        String modelId = blankToNull(explicitModel);
        if (modelId != null) {
            int slash = modelId.indexOf('/');
            if (slash > 0 && slash < modelId.length() - 1) {
                providerId = modelId.substring(0, slash);
                modelId = modelId.substring(slash + 1);
            }
        } else if (providerId != null) {
            throw new AetherConfigException("A model id is required when provider \"" + providerId + "\" is specified.");
        } else {
            providerId = defaultProvider;
            modelId = defaultModel;
        }

        ModelProviderConfig provider = modelProviders.get(providerId);
        if (provider == null) {
            throw new AetherConfigException("Model provider \"" + providerId + "\" is not configured.");
        }
        ModelDefinition modelDefinition = findModel(provider, modelId);
        if (modelDefinition == null) {
            throw new AetherConfigException("Model \"" + providerId + "/" + modelId + "\" is not configured.");
        }

        String api = firstNonBlank(modelDefinition.api(), provider.api());
        String baseUrl = firstNonBlank(modelDefinition.baseUrl(), provider.baseUrl());
        LlmModel model = LlmModel.builder()
                .provider(providerId)
                .api(api)
                .id(modelDefinition.id())
                .name(firstNonBlank(modelDefinition.name(), modelDefinition.id()))
                .baseUrl(baseUrl)
                .contextWindowTokens(modelDefinition.contextWindowTokens())
                .autoCompactTokenLimit(modelDefinition.autoCompactTokenLimit())
                .headers(mergeHeaders(provider.headers(), modelDefinition.headers()))
                .input(modelDefinition.input())
                .build();
        return new ModelSelection(
                model,
                requestAuth(providerId, provider, modelDefinition),
                reasoningFrom(selectedReasoningValue(defaultThinkingLevel, explicitReasoningEffort))
        );
    }

    public static ModelSelection selectCurrentModel(
            LlmModel currentModel,
            RequestAuth currentAuth,
            ReasoningOptions currentReasoning,
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        if (currentModel == null) {
            throw new AetherConfigException("No model is configured for this session.");
        }
        String providerId = blankToNull(explicitProvider);
        String modelId = blankToNull(explicitModel);
        if (modelId != null) {
            int slash = modelId.indexOf('/');
            if (slash > 0 && slash < modelId.length() - 1) {
                providerId = modelId.substring(0, slash);
                modelId = modelId.substring(slash + 1);
            }
        }
        if (providerId == null) {
            providerId = currentModel.getProvider();
        }
        if (modelId == null) {
            modelId = currentModel.getId();
        }
        if (!Objects.equals(providerId, currentModel.getProvider()) || !Objects.equals(modelId, currentModel.getId())) {
            throw new AetherConfigException("Model \"" + providerId + "/" + modelId + "\" is not configured.");
        }
        return new ModelSelection(
                currentModel,
                currentAuth,
                reasoningFromOrDefault(explicitReasoningEffort, currentReasoning)
        );
    }

    private static Map<String, ModelProviderConfig> copyProviders(Map<String, ModelProviderConfig> modelProviders) {
        if (modelProviders == null || modelProviders.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(modelProviders));
    }

    private static List<ModelOption> buildModelOptions(Map<String, ModelProviderConfig> modelProviders) {
        List<ModelOption> options = new ArrayList<>();
        for (Map.Entry<String, ModelProviderConfig> entry : modelProviders.entrySet()) {
            String providerId = entry.getKey();
            ModelProviderConfig provider = entry.getValue();
            if (provider == null || provider.models() == null) {
                continue;
            }
            for (ModelDefinition model : provider.models()) {
                if (model != null) {
                    options.add(modelOption(providerId, provider, model));
                }
            }
        }
        return List.copyOf(options);
    }

    private static ModelOption modelOption(
            String providerId,
            ModelProviderConfig provider,
            ModelDefinition model
    ) {
        String api = firstNonBlank(model.api(), provider.api());
        return new ModelOption(
                providerId,
                model.id(),
                firstNonBlank(model.name(), model.id()),
                api,
                model.contextWindowTokens(),
                model.autoCompactTokenLimit(),
                model.input()
        );
    }

    private static ModelDefinition findModel(ModelProviderConfig provider, String modelId) {
        for (ModelDefinition model : provider.models()) {
            if (model != null && model.id().equals(modelId)) {
                return model;
            }
        }
        return null;
    }

    private static RequestAuth requestAuth(
            String providerId,
            ModelProviderConfig provider,
            ModelDefinition model
    ) {
        String apiKey = null;
        if (!isBlank(provider.apiKey())) {
            apiKey = ConfigValueResolver.resolveConfigValueOrThrow(
                    provider.apiKey(),
                    "API key for provider \"" + providerId + "\""
            );
        }

        Map<String, String> headers = resolvedHeaders(providerId, provider, model);
        if (Boolean.TRUE.equals(provider.authHeader())) {
            if (isBlank(apiKey)) {
                throw new AetherConfigException("Provider \"" + providerId + "\" enables auth_header but api_key is missing.");
            }
            headers.put("Authorization", "Bearer " + apiKey);
        }

        if (isBlank(apiKey)) {
            throw new AetherConfigException("Provider \"" + providerId + "\" must define api_key.");
        }
        return RequestAuth.ok(apiKey, headers.isEmpty() ? null : headers);
    }

    private static Map<String, String> resolvedHeaders(
            String providerId,
            ModelProviderConfig provider,
            ModelDefinition model
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> providerHeaders = ConfigValueResolver.resolveHeadersOrThrow(
                provider.headers(),
                "provider \"" + providerId + "\""
        );
        if (providerHeaders != null) {
            headers.putAll(providerHeaders);
        }
        Map<String, String> modelHeaders = ConfigValueResolver.resolveHeadersOrThrow(
                model.headers(),
                "model \"" + providerId + "/" + model.id() + "\""
        );
        if (modelHeaders != null) {
            headers.putAll(modelHeaders);
        }
        return headers;
    }

    private static Map<String, String> mergeHeaders(Map<String, String> providerHeaders, Map<String, String> modelHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (providerHeaders != null) {
            headers.putAll(providerHeaders);
        }
        if (modelHeaders != null) {
            headers.putAll(modelHeaders);
        }
        return headers;
    }

    private static ReasoningOptions reasoningFrom(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        ReasoningOptions.ReasoningEffort effort;
        try {
            effort = ReasoningOptions.ReasoningEffort.valueOf(normalized.trim().replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AetherConfigException("Unknown default_thinking_level: " + value, e);
        }
        return ReasoningOptions.builder()
                .reasoningEffort(effort)
                .build();
    }

    private static ReasoningOptions reasoningFromOrDefault(String value, ReasoningOptions defaultReasoning) {
        String normalized = blankToNull(value);
        if (normalized == null || "default".equalsIgnoreCase(normalized)) {
            return defaultReasoning;
        }
        return reasoningFrom(normalized);
    }

    private static String selectedReasoningValue(String defaultThinkingLevel, String explicitReasoningEffort) {
        String normalized = blankToNull(explicitReasoningEffort);
        if (normalized == null || "default".equalsIgnoreCase(normalized)) {
            return defaultThinkingLevel;
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
