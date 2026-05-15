package io.github.lingjiuu.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.llm.ReasoningOptions;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsManager {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path settingsPath;
    private SettingsData data = new SettingsData();
    private String error;

    private SettingsManager(Path settingsPath) {
        this.settingsPath = settingsPath;
        reload();
    }

    public static SettingsManager create() {
        return new SettingsManager(AetherPaths.getSettingsPath());
    }

    public static SettingsManager create(Path settingsPath) {
        return new SettingsManager(settingsPath);
    }

    public synchronized void reload() {
        data = new SettingsData();
        error = null;
        ensureParentDir();
        if (!Files.exists(settingsPath)) {
            return;
        }
        try {
            SettingsData loaded = objectMapper.readValue(Files.readString(settingsPath), SettingsData.class);
            if (loaded != null) {
                data = loaded;
            }
        } catch (Exception e) {
            error = "Failed to load settings from " + settingsPath + ": " + e.getMessage();
        }
    }

    public synchronized String getDefaultProvider() {
        return blankToNull(data.defaultProvider);
    }

    public synchronized String getDefaultModel() {
        return blankToNull(data.defaultModel);
    }

    public synchronized String getError() {
        return error;
    }

    public synchronized ReasoningOptions.ReasoningEffort getDefaultThinkingLevel() {
        String value = blankToNull(data.defaultThinkingLevel);
        if (value == null) {
            return null;
        }
        try {
            return ReasoningOptions.ReasoningEffort.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public synchronized void setDefaultModelAndProvider(String provider, String modelId) {
        data.defaultProvider = provider;
        data.defaultModel = modelId;
        persist();
    }

    private void persist() {
        ensureParentDir();
        try {
            Files.writeString(settingsPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
            error = null;
        } catch (IOException e) {
            error = "Failed to save settings to " + settingsPath + ": " + e.getMessage();
        }
    }

    private void ensureParentDir() {
        try {
            Files.createDirectories(settingsPath.getParent());
        } catch (IOException ignored) {
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Getter
    @NoArgsConstructor
    public static class SettingsData {
        private String defaultProvider;
        private String defaultModel;
        private String defaultThinkingLevel;
    }
}
