package io.github.lingjiuu.tool.result;

import java.nio.file.Path;

public record PersistedToolOutput(
        Path path,
        long originalSizeBytes,
        String preview,
        boolean hasMore,
        boolean json
) {
}
