package io.github.lingjiuu.llm;

import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.provider.openai.OpenAiProvider;

public class LlmClient {

    private final ProviderRegistry providerRegistry;

    public LlmClient() {
        this(defaultRegistry());
    }

    public LlmClient(ProviderRegistry providerRegistry) {
        if (providerRegistry == null) {
            throw new IllegalArgumentException("providerRegistry must not be null");
        }
        this.providerRegistry = providerRegistry;
    }

    public AssistantStream stream(LlmRequest request) {
        if (request == null || request.getModel() == null) {
            throw new IllegalArgumentException("request model must not be null");
        }

        RequestAuth auth = request.getAuth();
        if (auth == null) {
            throw new IllegalStateException("LLM request auth must be resolved before invoking provider.");
        }
        if (!auth.isOk()) {
            throw new IllegalStateException(auth.getError());
        }

        Provider provider = providerRegistry.require(request.getModel().getApi());
        return provider.stream(request.toBuilder()
                .auth(auth)
                .callOptions(request.getCallOptions() == null ? LlmCallOptions.builder().build() : request.getCallOptions())
                .build());
    }

    public AssistantStream sample(LlmRequest request) {
        return stream(request);
    }

    private static ProviderRegistry defaultRegistry() {
        return new ProviderRegistry()
                .register(new OpenAiProvider());
    }
}
