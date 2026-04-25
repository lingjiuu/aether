package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.agent.runtime.RuntimeStatePatch;
import io.github.lingjiuu.message.Message;

import java.util.List;

public record TurnResult(
        List<Message> appendedMessages,
        List<AgentEvent> events,
        Transition transition,
        AgentRuntimeState.TerminationReason terminationReason,
        RuntimeStatePatch statePatch
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
        statePatch = statePatch == null ? RuntimeStatePatch.none() : statePatch;
    }

    public static TurnResult nextTurn(List<Message> appendedMessages, List<AgentEvent> events) {
        return nextTurn(appendedMessages, events, RuntimeStatePatch.none());
    }

    public static TurnResult nextTurn(
            List<Message> appendedMessages,
            List<AgentEvent> events,
            RuntimeStatePatch statePatch
    ) {
        return new TurnResult(appendedMessages, events, Transition.NEXT_TURN, null, statePatch);
    }

    public static TurnResult finish(
            List<Message> appendedMessages,
            List<AgentEvent> events,
            AgentRuntimeState.TerminationReason terminationReason
    ) {
        return finish(appendedMessages, events, terminationReason, RuntimeStatePatch.none());
    }

    public static TurnResult finish(
            List<Message> appendedMessages,
            List<AgentEvent> events,
            AgentRuntimeState.TerminationReason terminationReason,
            RuntimeStatePatch statePatch
    ) {
        return new TurnResult(appendedMessages, events, Transition.FINISH, terminationReason, statePatch);
    }

    public enum Transition {
        NEXT_TURN,
        FINISH
    }
}
