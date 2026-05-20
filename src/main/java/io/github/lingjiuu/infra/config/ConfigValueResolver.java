package io.github.lingjiuu.infra.config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigValueResolver {

    private static final Map<String, String> COMMAND_CACHE = new ConcurrentHashMap<>();

    private ConfigValueResolver() {
    }

    public static String resolveConfigValue(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        if (config.startsWith("$")) {
            return resolveEnvironmentVariable(config.substring(1));
        }
        if (config.startsWith("!")) {
            return COMMAND_CACHE.computeIfAbsent(config, ConfigValueResolver::executeCommandCached);
        }
        String envValue = System.getenv(config);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return looksLikeEnvironmentVariable(config) ? null : config;
    }

    public static String resolveConfigValueUncached(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        if (config.startsWith("$")) {
            return resolveEnvironmentVariable(config.substring(1));
        }
        if (config.startsWith("!")) {
            return executeCommand(config);
        }
        String envValue = System.getenv(config);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return looksLikeEnvironmentVariable(config) ? null : config;
    }

    public static String resolveConfigValueOrThrow(String config, String description) {
        String resolved = resolveConfigValueUncached(config);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        if (config != null && config.startsWith("!")) {
            throw new IllegalStateException("Failed to resolve " + description + " from shell command: " + config.substring(1));
        }
        throw new IllegalStateException("Failed to resolve " + description);
    }

    public static Map<String, String> resolveHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String value = resolveConfigValue(entry.getValue());
            if (value != null && !value.isBlank()) {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    public static Map<String, String> resolveHeadersOrThrow(Map<String, String> headers, String description) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            resolved.put(
                    entry.getKey(),
                    resolveConfigValueOrThrow(entry.getValue(), description + " header \"" + entry.getKey() + "\"")
            );
        }
        return resolved.isEmpty() ? null : resolved;
    }

    public static void clearCache() {
        COMMAND_CACHE.clear();
    }

    private static String executeCommandCached(String commandConfig) {
        return executeCommand(commandConfig);
    }

    private static String executeCommand(String commandConfig) {
        String command = commandConfig.substring(1);
        try {
            ProcessBuilder builder;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                builder = new ProcessBuilder("powershell", "-NoProfile", "-Command", command);
            } else {
                builder = new ProcessBuilder("sh", "-lc", command);
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String output = readAll(process.getInputStream()).trim();
            return output.isBlank() ? null : output;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readAll(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String resolveEnvironmentVariable(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean looksLikeEnvironmentVariable(String value) {
        return value.matches("[A-Z][A-Z0-9_]*");
    }
}
