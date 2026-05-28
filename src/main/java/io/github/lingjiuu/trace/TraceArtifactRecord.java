package io.github.lingjiuu.trace;

public record TraceArtifactRecord(
        String artifactId,
        String runId,
        String spanId,
        String kind,
        String path,
        String sha256,
        Long bytes,
        String mimeType,
        long createdAtMs
) {
}
