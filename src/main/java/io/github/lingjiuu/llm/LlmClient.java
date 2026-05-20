package io.github.lingjiuu.llm;

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

    public LlmClientSession openSession(LlmModel model, RequestAuth auth) {
        validate(model, auth);
        return new LlmClientSession(
                providerRegistry.require(model.getApi()).openSession(model, auth),
                model,
                auth
        );
    }

    private void validate(LlmModel model, RequestAuth auth) {
        if (model == null) {
            throw new IllegalArgumentException("request model must not be null");
        }
        if (auth == null) {
            throw new IllegalStateException("LLM request auth must be resolved before invoking provider.");
        }
        if (!auth.isOk()) {
            throw new IllegalStateException(auth.getError());
        }
    }

    private static ProviderRegistry defaultRegistry() {
        return new ProviderRegistry()
                .register(new OpenAiProvider());
    }
}
