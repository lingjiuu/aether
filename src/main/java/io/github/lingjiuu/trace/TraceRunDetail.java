package io.github.lingjiuu.trace;

import java.util.List;

public record TraceRunDetail(
        TraceRunRecord run,
        List<TraceSpanRecord> spans,
        List<TraceEventRecord> events,
        List<TraceArtifactRecord> artifacts
) {
}
