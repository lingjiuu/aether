package io.github.lingjiuu.agent.runtime;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.AgentEventListener;
import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.agent.turn.TurnResult;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.Message;

import java.util.List;

public class AgentRuntime {

    private final AgentRuntimeState state;
    private final AgentLoop agentLoop;

    public AgentRuntime(AgentRuntimeState state, AgentLoop agentLoop) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (agentLoop == null) {
            throw new IllegalArgumentException("agentLoop must not be null");
        }
        this.state = state;
        this.agentLoop = agentLoop;
    }

    public void run(AgentEventListener listener) {
        run(listener, AgentRunOptions.defaults());
    }

    public void run(AgentEventListener listener, AgentRunOptions options) {
        if (state.isEmpty()) {
            throw new IllegalStateException("Cannot run agent runtime without any messages in state.");
        }
        AgentRunOptions safeOptions = options == null ? AgentRunOptions.defaults() : options;

        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.RUN_START)
                .build());

        while (!state.isTerminal()) {
            if (safeOptions.cancellationToken().isCancellationRequested()) {
                finishAborted(listener);
                break;
            }
            TurnResult turnResult = agentLoop.runTurn(state, safeOptions);
            applyTurnResult(turnResult, listener);
        }

        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.RUN_END)
                .turn(state.currentTurn())
                .build());
    }

    public AgentRuntimeState state() {
        return state;
    }

    private void finishAborted(AgentEventListener listener) {
        AssistantMessage abortedMessage = AssistantMessage.builder()
                .stopReason(AssistantMessage.StopReason.ABORTED)
                .contents(List.of(TextContent.builder().text("").build()))
                .build();
        state.append(abortedMessage);
        state.finish(AgentRuntimeState.TerminationReason.ABORTED);
        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.ASSISTANT_MESSAGE)
                .turn(state.currentTurn())
                .assistantMessage(abortedMessage)
                .text("")
                .build());
    }

    private void applyTurnResult(TurnResult turnResult, AgentEventListener listener) {
        state.apply(turnResult.statePatch());

        int appendedMessageIndex = 0;
        for (AgentEvent event : turnResult.events()) {
            if (
                    event.getType() == AgentEvent.Type.ASSISTANT_MESSAGE
                            || event.getType() == AgentEvent.Type.TOOL_RESULT
            ) {
                if (appendedMessageIndex >= turnResult.appendedMessages().size()) {
                    throw new IllegalStateException("No pending message available for event: " + event.getType());
                }
                state.append(turnResult.appendedMessages().get(appendedMessageIndex++));
            }
            emit(listener, event);
        }

        if (appendedMessageIndex != turnResult.appendedMessages().size()) {
            throw new IllegalStateException("Unapplied messages remain after applyTurnResult.");
        }

        if (turnResult.transition() == TurnResult.Transition.NEXT_TURN) {
            state.advanceTurn();
            return;
        }

        state.finish(turnResult.terminationReason());
    }

    private void emit(AgentEventListener listener, AgentEvent event) {
        if (listener != null && event != null) {
            listener.onEvent(event);
        }
    }
}
