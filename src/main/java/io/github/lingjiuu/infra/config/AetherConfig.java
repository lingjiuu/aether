package io.github.lingjiuu.infra.config;

import java.util.List;
import java.util.Map;

public record AetherConfig(
        String defaultProvider,
        String defaultModel,
        String defaultThinkingLevel,
        Map<String, ModelProviderConfig> modelProviders
) {

    public AetherConfig {
        modelProviders = modelProviders == null ? Map.of() : Map.copyOf(modelProviders);
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
}
