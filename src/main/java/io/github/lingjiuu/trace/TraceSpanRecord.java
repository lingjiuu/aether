package io.github.lingjiuu.trace;

public record TraceSpanRecord(
        String spanId,
        String runId,
        String parentSpanId,
        String kind,
        String name,
        String status,
        long startedAtMs,
        Long endedAtMs,
        Long durationMs,
        String inputJson,
        String outputJson,
        String error
) {
}
