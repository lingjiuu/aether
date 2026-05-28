package io.github.lingjiuu.trace;

import java.util.List;
import java.util.Optional;

public interface AgentTraceStore extends AutoCloseable {

    void appendRunStarted(TraceRunRecord run);

    void appendRunFinished(String runId, String status, long endedAtMs, long durationMs, String error);

    void appendSpanStarted(TraceSpanRecord span);

    void appendSpanFinished(String spanId, String status, long endedAtMs, long durationMs, String outputJson, String error);

    void appendEvent(TraceEventRecord event);

    void appendArtifact(TraceArtifactRecord artifact);

    void flush();

    Optional<TraceRunDetail> readRun(String runId);

    List<TraceRunRecord> listRuns(int limit);

    @Override
    void close();
}
