package io.github.lingjiuu.model.client;

import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.wire.WireAdapterRegistry;
import io.github.lingjiuu.wire.openai.OpenAiResponsesAdapter;

public class ModelClient {

    private final WireAdapterRegistry wireAdapters;

    public ModelClient() {
        this(defaultRegistry());
    }

    public ModelClient(WireAdapterRegistry wireAdapters) {
        if (wireAdapters == null) {
            throw new IllegalArgumentException("wireAdapters must not be null");
        }
        this.wireAdapters = wireAdapters;
    }

    public ModelClientSession openSession(ModelSelection selection) {
        validate(selection);
        return new ModelClientSession(
                wireAdapters.require(selection.endpoint().wireApi()).openSession(selection)
        );
    }

    private void validate(ModelSelection selection) {
        if (selection == null || selection.model() == null || selection.endpoint() == null) {
            throw new IllegalArgumentException("model selection must not be null");
        }
        ProviderAuth auth = selection.auth();
        if (auth == null) {
            throw new IllegalStateException("model provider auth must be resolved before invoking provider.");
        }
        if (!auth.isOk()) {
            throw new IllegalStateException(auth.getError());
        }
    }

    private static WireAdapterRegistry defaultRegistry() {
        return new WireAdapterRegistry()
                .register(new OpenAiResponsesAdapter());
    }
}
