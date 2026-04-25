package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.invocation.AssistantStreamEventMapper;
import io.github.lingjiuu.agent.invocation.ModelInvocationResult;
import io.github.lingjiuu.agent.invocation.ModelInvoker;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.session.AgentSessionServices;

import java.util.ArrayList;
import java.util.List;

public class AgentLoop {

    private final TurnPreprocessor turnPreprocessor;
    private final ModelInvoker modelInvoker;
    private final TurnPostprocessor turnPostprocessor;

    public AgentLoop(AgentSessionServices services) {
        this(
                new TurnPreprocessor(services),
                new ModelInvoker(services.getLlmClient(), new AssistantStreamEventMapper()),
                new TurnPostprocessor(services.getToolRunner())
        );
    }

    public AgentLoop(
            TurnPreprocessor turnPreprocessor,
            ModelInvoker modelInvoker,
            TurnPostprocessor turnPostprocessor
    ) {
        if (turnPreprocessor == null) {
            throw new IllegalArgumentException("turnPreprocessor must not be null");
        }
        if (modelInvoker == null) {
            throw new IllegalArgumentException("modelInvoker must not be null");
        }
        if (turnPostprocessor == null) {
            throw new IllegalArgumentException("turnPostprocessor must not be null");
        }
        this.turnPreprocessor = turnPreprocessor;
        this.modelInvoker = modelInvoker;
        this.turnPostprocessor = turnPostprocessor;
    }

    public TurnResult runTurn(AgentRuntimeState runtimeState) {
        if (runtimeState == null) {
            throw new IllegalArgumentException("runtimeState must not be null");
        }
        if (runtimeState.isEmpty()) {
            throw new IllegalStateException("Cannot run agent loop without any messages in runtime state.");
        }
        return step(runtimeState);
    }

    private TurnResult step(AgentRuntimeState runtimeState) {
        int currentTurn = runtimeState.currentTurn();
        List<AgentEvent> events = new ArrayList<>();

        events.add(AgentEvent.builder()
                .type(AgentEvent.Type.TURN_START)
                .turn(currentTurn)
                .build());

        ModelInvocationResult sampledAssistantMessage = modelInvoker.invoke(
                turnPreprocessor.prepare(runtimeState),
                currentTurn
        );
        TurnResult postprocessed = turnPostprocessor.process(sampledAssistantMessage, currentTurn);
        events.addAll(postprocessed.events());

        if (postprocessed.transition() == TurnResult.Transition.NEXT_TURN) {
            return TurnResult.nextTurn(postprocessed.appendedMessages(), events);
        }
        return TurnResult.finish(postprocessed.appendedMessages(), events, postprocessed.terminationReason());
    }
}
