package io.github.lingjiuu;

import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.model.ReasoningOptions;
import io.github.lingjiuu.model.client.ModelRetryOptions;
import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;

import java.util.List;
import java.util.Map;

public final class TestModelSelections {

    private TestModelSelections() {
    }

    public static ModelSelection fakeSelection() {
        return fakeSelection(null);
    }

    public static ModelSelection fakeSelection(ReasoningOptions reasoning) {
        return new ModelSelection(
                ModelInfo.builder()
                        .id("fake-model")
                        .input(List.of("text"))
                        .contextWindowTokens(100_000L)
                        .build(),
                new ProviderEndpoint("fake", "fake", "http://fake.test/v1", Map.of(), ModelRetryOptions.defaults()),
                ProviderAuth.ok("test", Map.of()),
                reasoning
        );
    }
}
