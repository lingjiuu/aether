package io.github.lingjiuu.llm;

import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.provider.openai.OpenAiResponsesProvider;
import io.github.lingjiuu.session.ModelRegistry;

public class LlmClient {

    private final ModelRegistry modelRegistry;
    private final ProviderRegistry providerRegistry;

    public LlmClient(ModelRegistry modelRegistry) {
        this(modelRegistry, defaultRegistry());
    }

    public LlmClient(ModelRegistry modelRegistry, ProviderRegistry providerRegistry) {
        if (modelRegistry == null) {
            throw new IllegalArgumentException("modelRegistry must not be null");
        }
        if (providerRegistry == null) {
            throw new IllegalArgumentException("providerRegistry must not be null");
        }
        this.modelRegistry = modelRegistry;
        this.providerRegistry = providerRegistry;
    }

    public AssistantStream stream(LlmRequest request) {
        if (request == null || request.getConfig() == null || request.getConfig().getModel() == null) {
            throw new IllegalArgumentException("request config and model must not be null");
        }

        RequestAuth auth = request.getAuth() != null
                ? request.getAuth()
                : modelRegistry.getRequestAuth(request.getConfig().getModel());
        if (!auth.isOk()) {
            throw new IllegalStateException(auth.getError());
        }

        Provider provider = providerRegistry.require(request.getConfig().getModel().getApi());
        return provider.stream(request.toBuilder()
                .auth(auth)
                .callOptions(mergeOptions(request))
                .build());
    }

    public AssistantStream sample(LlmRequest request) {
        return stream(request);
    }

    private LlmCallOptions mergeOptions(LlmRequest request) {
        LlmCallOptions safeOptions = request.getCallOptions() == null ? LlmCallOptions.builder().build() : request.getCallOptions();
        return LlmCallOptions.builder()
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
