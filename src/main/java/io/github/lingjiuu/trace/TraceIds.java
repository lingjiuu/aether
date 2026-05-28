package io.github.lingjiuu.trace;

import java.util.UUID;

public final class TraceIds {

    private TraceIds() {
    }

    public static String runId() {
        return "run_" + UUID.randomUUID();
    }

    public static String spanId() {
        return "span_" + UUID.randomUUID();
    }

    public static String eventId() {
        return "event_" + UUID.randomUUID();
    }

    public static String artifactId() {
        return "artifact_" + UUID.randomUUID();
    }
}
