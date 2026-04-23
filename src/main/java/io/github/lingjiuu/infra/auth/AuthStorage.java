package io.github.lingjiuu.infra.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.infra.config.ConfigValueResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public class AuthStorage {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path authPath;
    private final Map<String, AuthCredential> data = new LinkedHashMap<>();
    private final Map<String, String> runtimeOverrides = new LinkedHashMap<>();
    private Function<String, String> fallbackResolver;

    private AuthStorage(Path authPath) {
        this.authPath = authPath;
        reload();
    }

    public static AuthStorage create() {
        return new AuthStorage(AetherPaths.getAuthPath());
    }

    public static AuthStorage create(Path authPath) {
        return new AuthStorage(authPath);
    }

    public synchronized void reload() {
        data.clear();
        ensureParentDir();
        if (!Files.exists(authPath)) {
            return;
        }
        try {
            Map<String, AuthCredential> loaded = objectMapper.readValue(
                    Files.readString(authPath),
                    new TypeReference<>() {
                    }
            );
            if (loaded != null) {
                data.putAll(loaded);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void setRuntimeApiKey(String provider, String apiKey) {
        runtimeOverrides.put(provider, apiKey);
    }

    public synchronized void removeRuntimeApiKey(String provider) {
        runtimeOverrides.remove(provider);
    }

    public synchronized void setFallbackResolver(Function<String, String> fallbackResolver) {
        this.fallbackResolver = fallbackResolver;
    }

    public synchronized AuthCredential get(String provider) {
        return data.get(provider);
    }

    public synchronized void set(String provider, AuthCredential credential) {
        data.put(provider, credential);
        persist();
    }

    public synchronized void remove(String provider) {
        data.remove(provider);
        persist();
    }

    public synchronized boolean has(String provider) {
        return data.containsKey(provider);
    }

    public synchronized boolean hasAuth(String provider) {
        return getApiKey(provider, true) != null;
    }

    public synchronized String getApiKey(String provider, boolean includeFallback) {
        String runtimeKey = runtimeOverrides.get(provider);
        if (runtimeKey != null && !runtimeKey.isBlank()) {
            return runtimeKey;
        }

        AuthCredential credential = data.get(provider);
        if (credential instanceof ApiKeyCredential apiKeyCredential) {
            return ConfigValueResolver.resolveConfigValue(apiKeyCredential.getKey());
        }

        String envKey = getEnvApiKey(provider);
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }

        if (includeFallback && fallbackResolver != null) {
            String fallbackValue = fallbackResolver.apply(provider);
            if (fallbackValue != null && !fallbackValue.isBlank()) {
                return fallbackValue;
            }
        }

        return null;
    }

    public synchronized String getApiKey(String provider) {
        return getApiKey(provider, true);
    }

    private void persist() {
        ensureParentDir();
        try {
            Files.writeString(authPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        } catch (IOException ignored) {
        }
    }

    private void ensureParentDir() {
        try {
            Files.createDirectories(authPath.getParent());
        } catch (IOException ignored) {
        }
    }

    private String getEnvApiKey(String provider) {
        return switch (provider) {
            case "openai" -> System.getenv("OPENAI_API_KEY");
            case "bailian" -> System.getenv("BAILIAN_API_KEY");
            case "siliconflow" -> System.getenv("SILICONFLOW_API_KEY");
            default -> null;
        };
    }
}
