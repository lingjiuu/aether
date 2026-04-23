package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.message.Message;

import java.util.List;

public record TurnResult(
        List<Message> appendedMessages,
        List<AgentEvent> events,
        Transition transition,
        AgentRuntimeState.TerminationReason terminationReason
) {

    public TurnResult {
        appendedMessages = appendedMessages == null ? List.of() : List.copyOf(appendedMessages);
        events = events == null ? List.of() : List.copyOf(events);
        if (transition == null) {
            throw new IllegalArgumentException("transition must not be null");
        }
        if (transition == Transition.FINISH && terminationReason == null) {
            throw new IllegalArgumentException("terminationReason must not be null when transition is FINISH");
        }
        if (transition == Transition.NEXT_TURN && terminationReason != null) {
            throw new IllegalArgumentException("terminationReason must be null when transition is NEXT_TURN");
        }
    }

    public static TurnResult nextTurn(List<Message> appendedMessages, List<AgentEvent> events) {
        return new TurnResult(appendedMessages, events, Transition.NEXT_TURN, null);
    }

    public static TurnResult finish(
            List<Message> appendedMessages,
            List<AgentEvent> events,
            AgentRuntimeState.TerminationReason terminationReason
    ) {
        return new TurnResult(appendedMessages, events, Transition.FINISH, terminationReason);
    }

    public enum Transition {
        NEXT_TURN,
        FINISH
    }
}
