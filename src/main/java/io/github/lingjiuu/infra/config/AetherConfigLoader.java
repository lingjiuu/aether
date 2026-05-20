package io.github.lingjiuu.infra.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class AetherConfigLoader {

    public AetherConfig load() {
        return load(AetherPaths.getConfigPath());
    }

    public AetherConfig load(Path configPath) {
        Path resolvedPath = configPath == null ? AetherPaths.getConfigPath() : configPath.toAbsolutePath().normalize();
        if (!Files.exists(resolvedPath)) {
            throw new AetherConfigException("Aether config not found: " + resolvedPath);
        }

        TomlParseResult parsed;
        try {
            parsed = Toml.parse(resolvedPath);
        } catch (IOException e) {
            throw new AetherConfigException("Failed to read Aether config: " + resolvedPath, e);
        }
        if (parsed.hasErrors()) {
            throw new AetherConfigException("Failed to parse Aether config " + resolvedPath + ": " + formatErrors(parsed.errors()));
        }

        AetherConfig config = new AetherConfig(
                blankToNull(parsed.getString("default_provider")),
                blankToNull(parsed.getString("default_model")),
                blankToNull(parsed.getString("default_thinking_level")),
                readProviders(parsed.getTable("model_providers"))
        );
        validate(config, resolvedPath);
        return config;
    }

    private Map<String, AetherConfig.ModelProviderConfig> readProviders(TomlTable providersTable) {
        if (providersTable == null || providersTable.isEmpty()) {
            return Map.of();
        }

        Map<String, AetherConfig.ModelProviderConfig> providers = new LinkedHashMap<>();
        for (String providerId : providersTable.keySet()) {
            TomlTable providerTable = providersTable.getTable(providerId);
            if (providerTable == null) {
                continue;
            }
            providers.put(providerId, new AetherConfig.ModelProviderConfig(
                    blankToNull(providerTable.getString("name")),
                    blankToNull(providerTable.getString("api")),
                    blankToNull(providerTable.getString("base_url")),
                    blankToNull(providerTable.getString("api_key")),
                    providerTable.getBoolean("auth_header"),
                    readStringMap(providerTable.getTable("headers")),
                    readModels(providerTable.getArray("models"))
            ));
        }
        return providers;
    }

    private List<AetherConfig.ModelDefinition> readModels(TomlArray modelsArray) {
        if (modelsArray == null || modelsArray.isEmpty()) {
            return List.of();
        }

        List<AetherConfig.ModelDefinition> models = new ArrayList<>();
        for (int i = 0; i < modelsArray.size(); i++) {
            TomlTable modelTable = modelsArray.getTable(i);
            if (modelTable == null) {
                continue;
            }
            models.add(new AetherConfig.ModelDefinition(
                    blankToNull(modelTable.getString("id")),
                    blankToNull(modelTable.getString("name")),
                    blankToNull(modelTable.getString("api")),
                    blankToNull(modelTable.getString("base_url")),
                    positiveLong(modelTable, "context_window"),
                    positiveLong(modelTable, "auto_compact_token_limit"),
                    readStringMap(modelTable.getTable("headers")),
                    readStringList(modelTable.getArray("input"))
            ));
        }
        return models;
    }

    private Map<String, String> readStringMap(TomlTable table) {
        if (table == null || table.isEmpty()) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object value = table.get(key);
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                values.put(key, stringValue);
            }
        }
        return values;
    }

    private List<String> readStringList(TomlArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                values.add(stringValue);
            }
        }
        return values;
    }

    private Long positiveLong(TomlTable table, String key) {
        if (table == null || key == null) {
            return null;
        }
        Long value = table.getLong(key);
        return value == null || value <= 0 ? null : value;
    }

    private void validate(AetherConfig config, Path configPath) {
        if (isBlank(config.defaultProvider())) {
            throw new AetherConfigException("Aether config " + configPath + " must define default_provider.");
        }
        if (isBlank(config.defaultModel())) {
            throw new AetherConfigException("Aether config " + configPath + " must define default_model.");
        }
        if (config.modelProviders().isEmpty()) {
            throw new AetherConfigException("Aether config " + configPath + " must define at least one model provider.");
        }
        if (!config.modelProviders().containsKey(config.defaultProvider())) {
            throw new AetherConfigException("default_provider \"" + config.defaultProvider() + "\" is not defined in model_providers.");
        }

        for (Map.Entry<String, AetherConfig.ModelProviderConfig> entry : config.modelProviders().entrySet()) {
            validateProvider(entry.getKey(), entry.getValue());
        }
    }

    private void validateProvider(String providerId, AetherConfig.ModelProviderConfig provider) {
        if (isBlank(providerId)) {
            throw new AetherConfigException("model provider id must not be blank.");
        }
        if (provider == null) {
            throw new AetherConfigException("model provider \"" + providerId + "\" config must not be empty.");
        }
        if (provider.models().isEmpty()) {
            throw new AetherConfigException("model provider \"" + providerId + "\" must define at least one model.");
        }
        for (AetherConfig.ModelDefinition model : provider.models()) {
            if (model == null || isBlank(model.id())) {
                throw new AetherConfigException("model provider \"" + providerId + "\" has a model without id.");
            }
            if (isBlank(firstNonBlank(model.api(), provider.api()))) {
                throw new AetherConfigException("model provider \"" + providerId + "\" model \"" + model.id() + "\" must define api or inherit provider api.");
            }
            if (isBlank(firstNonBlank(model.baseUrl(), provider.baseUrl()))) {
                throw new AetherConfigException("model provider \"" + providerId + "\" model \"" + model.id() + "\" must define base_url or inherit provider base_url.");
            }
        }
    }

    private String formatErrors(List<TomlParseError> errors) {
        StringJoiner joiner = new StringJoiner("; ");
        for (TomlParseError error : errors) {
            joiner.add(error.toString());
        }
        return joiner.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
