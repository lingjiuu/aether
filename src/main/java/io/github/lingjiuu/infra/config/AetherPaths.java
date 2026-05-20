package io.github.lingjiuu.infra.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AetherPaths {

    private AetherPaths() {
    }

    public static Path getAgentDir() {
        return Paths.get(System.getProperty("user.home"), ".aether");
    }

    public static Path getConfigPath() {
        return getAgentDir().resolve("config.toml");
    }

    public static Path getTranscriptsDir() {
        return getAgentDir().resolve("transcripts");
    }

    public static Path getToolsDir() {
        return getAgentDir().resolve("bin");
    }
}
