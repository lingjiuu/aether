package io.github.lingjiuu.session;

@FunctionalInterface
public interface AgentSessionEventListener {

    void onEvent(AgentSessionEvent event);
}
