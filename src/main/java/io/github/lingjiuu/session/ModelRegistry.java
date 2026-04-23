package io.github.lingjiuu.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.config.AetherPaths;
import io.github.lingjiuu.config.ConfigValueResolver;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.provider.RequestAuth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelRegistry {

    private static final String API_OPENAI = "openai";
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_BAILIAN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AuthStorage authStorage;
    private final Path modelsPath;
    private final List<LlmModel> models = new ArrayList<>();
    private final Map<String, ProviderRequestConfig> providerRequestConfigs = new LinkedHashMap<>();

    public ModelRegistry(AuthStorage authStorage) {
        this(authStorage, AetherPaths.getModelsPath());
    }

    public ModelRegistry(AuthStorage authStorage, Path modelsPath) {
        this.authStorage = authStorage;
        this.modelsPath = modelsPath;
        this.authStorage.setFallbackResolver(this::getProviderConfiguredApiKey);
        refresh();
    }

    public final void refresh() {
        models.clear();
        providerRequestConfigs.clear();
        loadBuiltInModels();
        loadCustomModels();
    }

    public LlmModel find(String provider, String modelId) {
        return models.stream()
                .filter(model -> model.getProvider().equals(provider) && model.getId().equals(modelId))
                .findFirst()
                .orElse(null);
    }

    public LlmModel require(String provider, String modelId) {
        LlmModel model = find(provider, modelId);
        if (model == null) {
            throw new IllegalArgumentException("No model found for " + provider + "/" + modelId);
        }
        return model;
    }

    public LlmModel findFirstAvailable() {
        return models.stream()
                .filter(this::hasConfiguredAuth)
                .findFirst()
                .orElse(models.isEmpty() ? null : models.getFirst());
    }

    public boolean hasConfiguredAuth(LlmModel model) {
        return authStorage.hasAuth(model.getProvider()) || providerRequestConfigs.get(model.getProvider()) != null;
    }

    public RequestAuth getRequestAuth(LlmModel model) {
        try {
            ProviderRequestConfig providerConfig = providerRequestConfigs.get(model.getProvider());
            String apiKeyFromAuthStorage = authStorage.getApiKey(model.getProvider(), false);
            String apiKey = apiKeyFromAuthStorage != null
                    ? apiKeyFromAuthStorage
                    : providerConfig != null && providerConfig.getApiKey() != null
                    ? ConfigValueResolver.resolveConfigValueOrThrow(
                    providerConfig.getApiKey(),
                    "API key for provider \"" + model.getProvider() + "\""
            )
                    : null;

            Map<String, String> providerHeaders = providerConfig == null
                    ? null
                    : ConfigValueResolver.resolveHeadersOrThrow(
                    providerConfig.getHeaders(),
                    "provider \"" + model.getProvider() + "\""
            );
            Map<String, String> modelHeaders = ConfigValueResolver.resolveHeadersOrThrow(
                    model.getHeaders(),
                    "model \"" + model.getProvider() + "/" + model.getId() + "\""
            );

            Map<String, String> headers = null;
            if ((providerHeaders != null && !providerHeaders.isEmpty()) || (modelHeaders != null && !modelHeaders.isEmpty())) {
                headers = new LinkedHashMap<>();
                if (providerHeaders != null) {
                    headers.putAll(providerHeaders);
                }
                if (modelHeaders != null) {
                    headers.putAll(modelHeaders);
                }
            }

            if (providerConfig != null && Boolean.TRUE.equals(providerConfig.getAuthHeader())) {
                if (apiKey == null || apiKey.isBlank()) {
                    return RequestAuth.error("No API key found for \"" + model.getProvider() + "\"");
                }
                if (headers == null) {
                    headers = new LinkedHashMap<>();
                }
                headers.put("Authorization", "Bearer " + apiKey);
            }

            return RequestAuth.ok(apiKey, headers);
        } catch (Exception e) {
            return RequestAuth.error(e.getMessage());
        }
    }

    private String getProviderConfiguredApiKey(String provider) {
        ProviderRequestConfig config = providerRequestConfigs.get(provider);
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return null;
        }
        return ConfigValueResolver.resolveConfigValueUncached(config.getApiKey());
    }

    private void loadBuiltInModels() {
        storeProviderRequestConfig("openai", ProviderRequestConfig.builder()
                .apiKey("OPENAI_API_KEY")
                .build());
        models.add(LlmModel.builder()
                .provider("openai")
                .api(API_OPENAI)
                .id("gpt-4.1")
                .name("GPT-4.1")
                .baseUrl(defaultBaseUrl("OPENAI_BASE_URL", DEFAULT_OPENAI_BASE_URL))
                .build());

        storeProviderRequestConfig("bailian", ProviderRequestConfig.builder()
                .apiKey("BAILIAN_API_KEY")
                .build());
        models.add(LlmModel.builder()
                .provider("bailian")
                .api(API_OPENAI)
                .id("qwen3.5-plus-2026-02-15")
                .name("Qwen 3.5 Plus")
                .baseUrl(defaultBaseUrl("BAILIAN_BASE_URL", DEFAULT_BAILIAN_BASE_URL))
                .build());

        storeProviderRequestConfig("siliconflow", ProviderRequestConfig.builder()
                .apiKey("SILICONFLOW_API_KEY")
                .build());
        models.add(LlmModel.builder()
                .provider("siliconflow")
                .api(API_OPENAI)
                .id("qwen3.5-plus-2026-02-15")
                .name("Qwen 3.5 Plus")
                .baseUrl(defaultBaseUrl("SILICONFLOW_BASE_URL", DEFAULT_SILICONFLOW_BASE_URL))
                .build());
    }

    private void loadCustomModels() {
        if (!Files.exists(modelsPath)) {
            return;
        }
        try {
            ModelsConfig config = objectMapper.readValue(Files.readString(modelsPath), ModelsConfig.class);
            if (config == null || config.getProviders() == null) {
                return;
            }
            for (Map.Entry<String, ProviderConfigInput> entry : config.getProviders().entrySet()) {
                applyProviderConfig(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
        }
    }

    private void applyProviderConfig(String providerName, ProviderConfigInput config) {
        storeProviderRequestConfig(providerName, ProviderRequestConfig.builder()
                .apiKey(config.getApiKey())
                .headers(config.getHeaders())
                .authHeader(config.getAuthHeader())
                .build());

        if (config.getModels() != null && !config.getModels().isEmpty()) {
            models.removeIf(model -> model.getProvider().equals(providerName));
            for (ModelDefinition modelDefinition : config.getModels()) {
                String api = modelDefinition.getApi() != null ? modelDefinition.getApi() : config.getApi();
                String baseUrl = modelDefinition.getBaseUrl() != null ? modelDefinition.getBaseUrl() : config.getBaseUrl();
                models.add(LlmModel.builder()
                        .provider(providerName)
                        .api(api)
                        .id(modelDefinition.getId())
                        .name(modelDefinition.getName())
                        .baseUrl(baseUrl)
                        .headers(modelDefinition.getHeaders() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(modelDefinition.getHeaders()))
                        .build());
            }
            return;
        }

        if (config.getBaseUrl() != null || (config.getHeaders() != null && !config.getHeaders().isEmpty())) {
            for (int i = 0; i < models.size(); i++) {
                LlmModel current = models.get(i);
                if (!current.getProvider().equals(providerName)) {
                    continue;
                }
                Map<String, String> mergedHeaders = new LinkedHashMap<>(current.getHeaders());
                if (config.getHeaders() != null) {
                    mergedHeaders.putAll(config.getHeaders());
                }
                models.set(i, LlmModel.builder()
                        .provider(current.getProvider())
                        .api(current.getApi())
                        .id(current.getId())
                        .name(current.getName())
                        .baseUrl(config.getBaseUrl() != null ? config.getBaseUrl() : current.getBaseUrl())
                        .headers(mergedHeaders)
                        .build());
            }
        }
    }

    private void storeProviderRequestConfig(String providerName, ProviderRequestConfig config) {
        if ((config.getApiKey() == null || config.getApiKey().isBlank())
                && (config.getHeaders() == null || config.getHeaders().isEmpty())
                && !Boolean.TRUE.equals(config.getAuthHeader())) {
            return;
        }
        providerRequestConfigs.put(providerName, config);
    }

    private String defaultBaseUrl(String envName, String defaultValue) {
        String env = System.getenv(envName);
        return env == null || env.isBlank() ? defaultValue : env;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderRequestConfig {
        private String apiKey;
        private Map<String, String> headers;
        private Boolean authHeader;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelsConfig {
        private Map<String, ProviderConfigInput> providers = new LinkedHashMap<>();
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderConfigInput {
        private String baseUrl;
        private String apiKey;
        private String api;
        private Map<String, String> headers;
        private Boolean authHeader;
        private List<ModelDefinition> models = new ArrayList<>();
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelDefinition {
        private String id;
        private String name;
        private String api;
        private String baseUrl;
        private Map<String, String> headers = new LinkedHashMap<>();
    }
}
