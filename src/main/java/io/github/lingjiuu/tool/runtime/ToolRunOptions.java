package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.tool.ToolCancellationToken;

import java.time.Duration;
import java.time.Instant;

public final class ToolRunOptions {

    private static final ToolRunOptions DEFAULTS = new ToolRunOptions(ToolCancellationToken.none(), null);

    private final ToolCancellationToken cancellationToken;
    private final Instant deadline;

    private ToolRunOptions(ToolCancellationToken cancellationToken, Instant deadline) {
        this.cancellationToken = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
        this.deadline = deadline;
    }

    public static ToolRunOptions defaults() {
        return DEFAULTS;
    }

    public static ToolRunOptions withCancellationToken(ToolCancellationToken token) {
        return of(token, null);
    }

    public static ToolRunOptions withTimeout(Duration timeout) {
        return of(null, timeout);
    }

    public static ToolRunOptions of(ToolCancellationToken token, Duration timeout) {
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        return new ToolRunOptions(token, deadline);
    }

    public ToolCancellationToken cancellationToken() {
        return cancellationToken;
    }

    public Instant deadline() {
        return deadline;
    }
}
