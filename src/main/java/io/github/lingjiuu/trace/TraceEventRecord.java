package io.github.lingjiuu.trace;

public record TraceEventRecord(
        String eventId,
        String runId,
        String spanId,
        Long sequence,
        String type,
        String payloadJson,
        long timestampMs
) {
}
