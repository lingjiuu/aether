package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.infra.config.AetherPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

final class Ripgrep {

    private static final String BUNDLED_VERSION = "15.1.0";
    private static final String RESOURCE_ROOT = "/vendor/ripgrep";

    private Ripgrep() {
    }

    static Optional<String> command() {
        Optional<String> systemRipgrep = ExecutableFinder.findOnPath(executableName());
        if (systemRipgrep.isPresent()) {
            return systemRipgrep;
        }
        return bundledCommand();
    }

    static Optional<String> bundledCommand() {
        String platform = platformKey();
        if (platform == null) {
            return Optional.empty();
        }

        Path command = bundledPath(platform);
        if (isUsableCommand(command)) {
            return Optional.of(command.toString());
        }
        return materializeBundledCommand(platform, command);
    }

    private static synchronized Optional<String> materializeBundledCommand(String platform, Path command) {
        if (isUsableCommand(command)) {
            return Optional.of(command.toString());
        }

        String resourcePath = RESOURCE_ROOT + "/" + platform + "/" + executableName();
        try (InputStream input = Ripgrep.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return Optional.empty();
            }

            Files.createDirectories(command.getParent());
            Path temp = Files.createTempFile(command.getParent(), executableName(), ".tmp");
            try {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(temp);
                moveIntoPlace(temp, command);
            } finally {
                Files.deleteIfExists(temp);
            }
            return isUsableCommand(command) ? Optional.of(command.toString()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isUsableCommand(Path command) {
        if (isWindows()) {
            return Files.isRegularFile(command);
        }
        return Files.isExecutable(command);
    }

    private static Path bundledPath(String platform) {
        return AetherPaths.getToolsDir()
                .resolve("ripgrep")
                .resolve(BUNDLED_VERSION)
                .resolve(platform)
                .resolve(executableName());
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(Path path) {
        if (!isWindows()) {
            path.toFile().setExecutable(true, true);
        }
    }

    private static String executableName() {
        return isWindows() ? "rg.exe" : "rg";
    }

    static String platformKey() {
        String os = normalizeOs(System.getProperty("os.name"));
        String arch = normalizeArch(System.getProperty("os.arch"));
        if (os == null || arch == null) {
            return null;
        }
        return arch + "-" + os;
    }

    private static String normalizeOs(String osName) {
        String value = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (value.contains("mac") || value.contains("darwin")) {
            return "darwin";
        }
        if (value.contains("linux")) {
            return "linux";
        }
        if (value.contains("win")) {
            return "win32";
        }
        return null;
    }

    private static String normalizeArch(String archName) {
        String value = archName == null ? "" : archName.toLowerCase(Locale.ROOT);
        if (value.equals("x86_64") || value.equals("amd64")) {
            return "x64";
        }
        if (value.equals("aarch64") || value.equals("arm64")) {
            return "arm64";
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
