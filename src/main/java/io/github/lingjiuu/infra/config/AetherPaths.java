package io.github.lingjiuu.infra.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AetherPaths {

    private AetherPaths() {
    }

    public static Path getAgentDir() {
        return Paths.get(System.getProperty("user.home"), ".aether");
    }

    public static Path getAuthPath() {
        return getAgentDir().resolve("auth.json");
    }

    public static Path getModelsPath() {
        return getAgentDir().resolve("models.json");
    }
}
