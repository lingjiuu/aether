package io.github.lingjiuu.trace;

import java.nio.file.Path;

public record TraceContext(
        String runId,
        String sessionId,
        String turnId,
        Integer turn,
        String commandId,
        String taskKind,
        Path cwd,
        String modelProvider,
        String modelId
) {

    public boolean enabled() {
        return runId != null && !runId.isBlank();
    }
}
