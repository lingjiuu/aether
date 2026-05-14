package io.github.lingjiuu.agent.runtime;

import io.github.lingjiuu.tool.ToolCancellationToken;

public final class AgentRunOptions {

    private static final AgentRunOptions DEFAULTS = new AgentRunOptions(ToolCancellationToken.none());

    private final ToolCancellationToken cancellationToken;

    private AgentRunOptions(ToolCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
    }

    public static AgentRunOptions defaults() {
        return DEFAULTS;
    }

    public static AgentRunOptions withCancellationToken(ToolCancellationToken cancellationToken) {
        return new AgentRunOptions(cancellationToken);
    }

    public ToolCancellationToken cancellationToken() {
        return cancellationToken;
    }
}
