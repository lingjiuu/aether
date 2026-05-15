package io.github.lingjiuu.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.infra.config.ConfigValueResolver;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.provider.RequestAuth;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
    private String loadError;

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
        loadError = null;
        loadBuiltInModels();
        loadCustomModels();
    }

    public String getError() {
        return loadError;
    }

    public List<LlmModel> getAll() {
        return List.copyOf(models);
    }

    public List<LlmModel> getAvailable() {
        return models.stream()
                .filter(this::hasConfiguredAuth)
                .toList();
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

    public boolean hasConfiguredAuth(LlmModel model) {
        if (authStorage.getApiKey(model.getProvider(), false) != null) {
            return true;
        }
        ProviderRequestConfig providerConfig = providerRequestConfigs.get(model.getProvider());
        return hasProviderConfigApiKey(providerConfig);
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
        if (!hasProviderConfigApiKey(config)) {
            return null;
        }
        return ConfigValueResolver.resolveConfigValueUncached(config.getApiKey());
    }

    private boolean hasProviderConfigApiKey(ProviderRequestConfig config) {
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return false;
        }
        String apiKey = config.getApiKey();
        String envValue = System.getenv(apiKey);
        if (envValue != null && !envValue.isBlank()) {
            return true;
        }
        if (apiKey.matches("[A-Z][A-Z0-9_]*")) {
            return false;
        }
        String resolved = ConfigValueResolver.resolveConfigValueUncached(apiKey);
        return resolved != null && !resolved.isBlank();
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
                .baseUrl(DEFAULT_OPENAI_BASE_URL)
                .input(List.of("text", "image"))
                .build());

        storeProviderRequestConfig("bailian", ProviderRequestConfig.builder()
                .apiKey("BAILIAN_API_KEY")
                .build());
        models.add(LlmModel.builder()
                .provider("bailian")
                .api(API_OPENAI)
                .id("qwen3.5-plus-2026-02-15")
                .name("Qwen 3.5 Plus")
                .baseUrl(DEFAULT_BAILIAN_BASE_URL)
                .input(List.of("text", "image"))
                .build());

        storeProviderRequestConfig("siliconflow", ProviderRequestConfig.builder()
                .apiKey("SILICONFLOW_API_KEY")
                .build());
        models.add(LlmModel.builder()
                .provider("siliconflow")
                .api(API_OPENAI)
                .id("qwen3.5-plus-2026-02-15")
                .name("Qwen 3.5 Plus")
                .baseUrl(DEFAULT_SILICONFLOW_BASE_URL)
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
        } catch (Exception e) {
            loadError = "Failed to load models from " + modelsPath + ": " + e.getMessage();
        }
    }

    private void applyProviderConfig(String providerName, ProviderConfigInput config) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("provider name must not be blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("provider \"" + providerName + "\" config must not be null");
        }
        validateProviderConfig(providerName, config);

        LlmModel providerDefault = firstModelForProvider(providerName);
        storeProviderRequestConfig(providerName, ProviderRequestConfig.builder()
                .apiKey(config.getApiKey())
                .headers(config.getHeaders())
                .authHeader(config.getAuthHeader())
                .build());

        applyBuiltInOverrides(providerName, config);

        if (config.getModels() != null && !config.getModels().isEmpty()) {
            for (ModelDefinition modelDefinition : config.getModels()) {
                LlmModel current = find(providerName, modelDefinition.getId());
                LlmModel defaults = current != null ? current : providerDefault;
                String api = firstNonBlank(modelDefinition.getApi(), config.getApi(), defaults == null ? null : defaults.getApi());
                String baseUrl = firstNonBlank(modelDefinition.getBaseUrl(), config.getBaseUrl(), defaults == null ? null : defaults.getBaseUrl());
                LlmModel customModel = LlmModel.builder()
                        .provider(providerName)
                        .api(api)
                        .id(modelDefinition.getId())
                        .name(firstNonBlank(modelDefinition.getName(), defaults == null ? null : defaults.getName(), modelDefinition.getId()))
                        .baseUrl(baseUrl)
                        .headers(copyHeaders(modelDefinition.getHeaders()))
                        .input(modelDefinition.getInput() == null || modelDefinition.getInput().isEmpty()
                                ? List.of("text")
                                : List.copyOf(modelDefinition.getInput()))
                        .build();
                upsertModel(customModel);
            }
        }
    }

    private void applyBuiltInOverrides(String providerName, ProviderConfigInput config) {
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
                        .input(current.getInput())
                        .build());
            }
        }
    }

    private void validateProviderConfig(String providerName, ProviderConfigInput config) {
        boolean hasModels = config.getModels() != null && !config.getModels().isEmpty();
        boolean isBuiltInProvider = firstModelForProvider(providerName) != null;

        if (!hasModels && isBlank(config.getBaseUrl()) && isBlank(config.getApiKey())
                && (config.getHeaders() == null || config.getHeaders().isEmpty())
                && config.getAuthHeader() == null) {
            throw new IllegalArgumentException("provider \"" + providerName + "\" config is empty");
        }

        if (hasModels) {
            for (ModelDefinition modelDefinition : config.getModels()) {
                if (modelDefinition == null || isBlank(modelDefinition.getId())) {
                    throw new IllegalArgumentException("provider \"" + providerName + "\" model id must not be blank");
                }
            }
        }

        if (hasModels && !isBuiltInProvider) {
            if (isBlank(config.getBaseUrl())) {
                throw new IllegalArgumentException("provider \"" + providerName + "\" must define baseUrl");
            }
            if (isBlank(config.getApiKey())) {
                throw new IllegalArgumentException("provider \"" + providerName + "\" must define apiKey");
            }
            if (isBlank(config.getApi())) {
                for (ModelDefinition modelDefinition : config.getModels()) {
                    if (isBlank(modelDefinition.getApi())) {
                        throw new IllegalArgumentException("provider \"" + providerName + "\" must define api or per-model api");
                    }
                }
            }
        }
    }

    private LlmModel firstModelForProvider(String providerName) {
        return models.stream()
                .filter(model -> model.getProvider().equals(providerName))
                .findFirst()
                .orElse(null);
    }

    private void upsertModel(LlmModel customModel) {
        for (int i = 0; i < models.size(); i++) {
            LlmModel current = models.get(i);
            if (current.getProvider().equals(customModel.getProvider()) && current.getId().equals(customModel.getId())) {
                models.set(i, customModel);
                return;
            }
        }
        models.add(customModel);
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    private void storeProviderRequestConfig(String providerName, ProviderRequestConfig config) {
        if ((config.getApiKey() == null || config.getApiKey().isBlank())
                && (config.getHeaders() == null || config.getHeaders().isEmpty())
                && !Boolean.TRUE.equals(config.getAuthHeader())) {
            return;
        }
        providerRequestConfigs.put(providerName, config);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
        private List<String> input = Collections.emptyList();
    }
}
