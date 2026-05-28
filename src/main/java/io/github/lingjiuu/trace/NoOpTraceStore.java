package io.github.lingjiuu.trace;

import java.util.List;
import java.util.Optional;

final class NoOpTraceStore implements AgentTraceStore {

    @Override
    public void appendRunStarted(TraceRunRecord run) {
    }

    @Override
    public void appendRunFinished(String runId, String status, long endedAtMs, long durationMs, String error) {
    }

    @Override
    public void appendSpanStarted(TraceSpanRecord span) {
    }

    @Override
    public void appendSpanFinished(String spanId, String status, long endedAtMs, long durationMs, String outputJson, String error) {
    }

    @Override
    public void appendEvent(TraceEventRecord event) {
    }

    @Override
    public void appendArtifact(TraceArtifactRecord artifact) {
    }

    @Override
    public void flush() {
    }

    @Override
    public Optional<TraceRunDetail> readRun(String runId) {
        return Optional.empty();
    }

    @Override
    public List<TraceRunRecord> listRuns(int limit) {
        return List.of();
    }

    @Override
    public void close() {
    }
}
