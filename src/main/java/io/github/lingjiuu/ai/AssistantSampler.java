package io.github.lingjiuu.ai;

import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.provider.openai.OpenAiResponsesProvider;
import io.github.lingjiuu.stream.AssistantStream;

public class AssistantSampler {

    private final ModelRegistry modelRegistry;
    private final ProviderRegistry providerRegistry;

    public AssistantSampler(ModelRegistry modelRegistry) {
        this(modelRegistry, defaultRegistry());
    }

    public AssistantSampler(ModelRegistry modelRegistry, ProviderRegistry providerRegistry) {
        if (modelRegistry == null) {
            throw new IllegalArgumentException("modelRegistry must not be null");
        }
        if (providerRegistry == null) {
            throw new IllegalArgumentException("providerRegistry must not be null");
        }
        this.modelRegistry = modelRegistry;
        this.providerRegistry = providerRegistry;
    }

    public AssistantStream stream(AssistantRequest request) {
        if (request == null || request.getConfig() == null || request.getConfig().getModel() == null) {
            throw new IllegalArgumentException("request config and model must not be null");
        }

        ResolvedRequestAuth auth = modelRegistry.getApiKeyAndHeaders(request.getConfig().getModel());
        if (!auth.isOk()) {
            throw new IllegalStateException(auth.getError());
        }

        Provider provider = providerRegistry.require(request.getConfig().getModel().getApi());
        return provider.stream(request.toBuilder()
                .options(mergeOptions(request, auth))
                .build());
    }

    public AssistantStream sample(AssistantRequest request) {
        return stream(request);
    }

    private ProviderOptions mergeOptions(AssistantRequest request, ResolvedRequestAuth auth) {
        ProviderOptions safeOptions = request.getOptions() == null ? ProviderOptions.builder().build() : request.getOptions();
        return ProviderOptions.builder()
                .apiKey(auth.getApiKey())
                .headers(auth.getHeaders())
                .temperature(safeOptions.getTemperature())
                .maxTokens(safeOptions.getMaxTokens())
                .reasoning(safeOptions.getReasoning() != null
                        ? safeOptions.getReasoning()
                        : request.getConfig().getReasoning())
                .build();
    }

    private static ProviderRegistry defaultRegistry() {
        return new ProviderRegistry()
                .register(new OpenAiResponsesProvider());
    }
}
