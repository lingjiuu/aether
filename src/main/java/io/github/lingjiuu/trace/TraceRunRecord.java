package io.github.lingjiuu.trace;

public record TraceRunRecord(
        String runId,
        String sessionId,
        String turnId,
        Integer turn,
        String commandId,
        String taskKind,
        String cwd,
        String modelProvider,
        String modelId,
        String status,
        long startedAtMs,
        Long endedAtMs,
        Long durationMs,
        String error
) {
}
