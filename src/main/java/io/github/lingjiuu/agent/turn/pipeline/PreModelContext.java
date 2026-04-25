package io.github.lingjiuu.agent.turn.pipeline;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.session.AgentSessionServices;

public record PreModelContext(
        AgentSessionServices services,
        AgentRuntimeState runtimeState
) {

    public PreModelContext {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        if (runtimeState == null) {
            throw new IllegalArgumentException("runtimeState must not be null");
        }
    }
}
