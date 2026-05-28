package io.github.lingjiuu.trace;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TraceSpan implements AutoCloseable {

    private final AgentTraceRecorder recorder;
    private final TraceContext context;
    private final String spanId;
    private final long startedAtMs;
    private final AtomicBoolean finished = new AtomicBoolean();

    TraceSpan(AgentTraceRecorder recorder, TraceContext context, String spanId, long startedAtMs) {
        this.recorder = recorder;
        this.context = context;
        this.spanId = spanId;
        this.startedAtMs = startedAtMs;
    }

    public String id() {
        return spanId;
    }

    public boolean enabled() {
        return context != null && context.enabled() && spanId != null;
    }

    public void succeed(Map<String, Object> output) {
        finish("COMPLETED", output, null);
    }

    public void finish(String status, Map<String, Object> output) {
        finish(status, output, null);
    }

    public void fail(Throwable throwable) {
        finish("FAILED", null, throwable == null ? null : throwable.getMessage());
    }

    public void fail(String message) {
        finish("FAILED", null, message);
    }

    private void finish(String status, Map<String, Object> output, String error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        recorder.finishSpan(this, status, output, error);
    }

    long startedAtMs() {
        return startedAtMs;
    }

    @Override
    public void close() {
        if (finished.compareAndSet(false, true)) {
            recorder.finishSpan(this, "COMPLETED", null, null);
        }
    }
}
