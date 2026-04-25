package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.runtime.RuntimeStatePatch;
import io.github.lingjiuu.llm.LlmRequest;

public record PreparedTurn(
        LlmRequest request,
        RuntimeStatePatch statePatch
) {

    public PreparedTurn {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        statePatch = statePatch == null ? RuntimeStatePatch.none() : statePatch;
    }
}
