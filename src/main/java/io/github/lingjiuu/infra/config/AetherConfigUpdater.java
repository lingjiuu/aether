package io.github.lingjiuu.infra.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

public class AetherConfigUpdater {

    public boolean addModelIfMissing(Path configPath, String providerId, String modelId) {
        Path resolvedPath = configPath == null ? AetherPaths.getConfigPath() : configPath.toAbsolutePath().normalize();
        if (isBlank(providerId) || isBlank(modelId)) {
            return false;
        }

        String content;
        TomlParseResult parsed;
        try {
            content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            parsed = Toml.parse(content);
        } catch (IOException e) {
            throw new AetherConfigException("Failed to read Aether config: " + resolvedPath, e);
        }
        if (parsed.hasErrors()) {
            throw new AetherConfigException("Failed to parse Aether config " + resolvedPath + ": " + formatErrors(parsed.errors()));
        }

        TomlTable providers = parsed.getTable("model_providers");
        TomlTable provider = providers == null ? null : providers.getTable(providerId);
        if (provider == null) {
            throw new AetherConfigException("Model provider \"" + providerId + "\" is not configured.");
        }
        if (hasModel(provider, modelId)) {
            return false;
        }
        if (isBlank(provider.getString("api")) || isBlank(provider.getString("base_url"))) {
            throw new AetherConfigException("Custom model \"" + providerId + "/" + modelId + "\" cannot inherit provider api/base_url.");
        }

        try {
            Files.writeString(resolvedPath, content + modelBlock(content, providerId, modelId), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AetherConfigException("Failed to update Aether config: " + resolvedPath, e);
        }
        return true;
    }

    private boolean hasModel(TomlTable provider, String modelId) {
        TomlArray models = provider.getArray("models");
        if (models == null) {
            return false;
        }
        for (int i = 0; i < models.size(); i++) {
            TomlTable model = models.getTable(i);
            if (model != null && modelId.equals(model.getString("id"))) {
                return true;
            }
        }
        return false;
    }

    private String modelBlock(String content, String providerId, String modelId) {
        String prefix = content.endsWith("\n") ? "\n" : "\n\n";
        return prefix + String.join("\n",
                "[[model_providers." + tomlKey(providerId) + ".models]]",
                "id = " + tomlString(modelId),
                "name = " + tomlString(modelId),
                "input = [\"text\", \"image\"]",
                ""
        );
    }

    private String formatErrors(List<TomlParseError> errors) {
        StringJoiner joiner = new StringJoiner("; ");
        for (TomlParseError error : errors) {
            joiner.add(error.toString());
        }
        return joiner.toString();
    }

    private String tomlString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private String tomlKey(String value) {
        return value.matches("[A-Za-z0-9_-]+") ? value : tomlString(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
