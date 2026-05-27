package io.github.lingjiuu.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ExecutableFinder {

    private ExecutableFinder() {
    }

    public static Optional<String> findOnPath(String executable) {
        if (executable == null || executable.isBlank()) {
            return Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String separator = System.getProperty("path.separator");
        for (String entry : path.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(executable);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate.toString());
            }
        }
        return Optional.empty();
    }
}
