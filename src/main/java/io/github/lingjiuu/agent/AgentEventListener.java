package io.github.lingjiuu.agent;

@FunctionalInterface
public interface AgentEventListener {

    void onEvent(AgentEvent event);
}
