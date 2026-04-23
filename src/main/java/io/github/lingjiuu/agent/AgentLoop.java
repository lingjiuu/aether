package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.session.AgentSessionServices;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

public class AgentLoop {

    private final TurnPreprocessor turnPreprocessor;
    private final ModelInvoker modelInvoker;
    private final ToolRegistry toolRegistry;

    public AgentLoop(AgentSessionServices services) {
        this(
                services,
                new DefaultContextTransformer(),
                new DefaultLlmMessageConverter(),
                new AssistantStreamEventMapper()
        );
    }

    public AgentLoop(
            AgentSessionServices services,
            ContextTransformer contextTransformer,
            LlmMessageConverter llmMessageConverter,
            AssistantStreamEventMapper assistantStreamEventMapper
    ) {
        this(
                new TurnPreprocessor(services, contextTransformer, llmMessageConverter),
                new ModelInvoker(services.getLlmClient(), assistantStreamEventMapper),
                services.getToolRegistry()
        );
    }

    public AgentLoop(
            TurnPreprocessor turnPreprocessor,
            ModelInvoker modelInvoker,
            ToolRegistry toolRegistry
    ) {
        if (turnPreprocessor == null) {
            throw new IllegalArgumentException("turnPreprocessor must not be null");
        }
        if (modelInvoker == null) {
            throw new IllegalArgumentException("modelInvoker must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        this.turnPreprocessor = turnPreprocessor;
        this.modelInvoker = modelInvoker;
        this.toolRegistry = toolRegistry;
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
        List<Message> appendedMessages = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();

        events.add(AgentEvent.builder()
                .type(AgentEvent.Type.TURN_START)
                .turn(currentTurn)
                .build());

        ModelInvocationResult sampledAssistantMessage = modelInvoker.invoke(
                turnPreprocessor.prepare(runtimeState),
                currentTurn
        );
        appendedMessages.add(sampledAssistantMessage.assistantMessage());
        events.addAll(sampledAssistantMessage.streamEvents());
        events.add(AgentEvent.builder()
                .type(AgentEvent.Type.ASSISTANT_MESSAGE)
                .turn(currentTurn)
                .assistantMessage(sampledAssistantMessage.assistantMessage())
                .text(sampledAssistantMessage.assistantText())
                .build());

        if (sampledAssistantMessage.assistantMessage().getStopReason() == AssistantMessage.StopReason.ERROR) {
            return TurnResult.finish(
                    appendedMessages,
                    events,
                    AgentRuntimeState.TerminationReason.FAILED
            );
        }
        if (sampledAssistantMessage.assistantMessage().getStopReason() == AssistantMessage.StopReason.ABORTED) {
            return TurnResult.finish(
                    appendedMessages,
                    events,
                    AgentRuntimeState.TerminationReason.ABORTED
            );
        }

        if (sampledAssistantMessage.toolCalls().isEmpty()) {
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.FINAL_ANSWER)
                    .turn(currentTurn)
                    .assistantMessage(sampledAssistantMessage.assistantMessage())
                    .text(sampledAssistantMessage.assistantText())
                    .build());
            return TurnResult.finish(
                    appendedMessages,
                    events,
                    AgentRuntimeState.TerminationReason.COMPLETED
            );
        }

        ToolCallStep toolCallStep = executeToolCalls(
                sampledAssistantMessage.assistantMessage(),
                sampledAssistantMessage.toolCalls(),
                currentTurn
        );
        appendedMessages.addAll(toolCallStep.toolResults());
        events.addAll(toolCallStep.events());

        return TurnResult.nextTurn(appendedMessages, events);
    }

    private ToolCallStep executeToolCalls(
            AssistantMessage assistantMessage,
            List<ToolCallContent> toolCalls,
            int turn
    ) {
        List<ToolResultMessage> toolResults = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();

        for (ToolCallContent toolCall : toolCalls) {
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_CALL)
                    .turn(turn)
                    .toolCall(toolCall)
                    .build());
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_EXECUTION_START)
                    .turn(turn)
                    .toolCall(toolCall)
                    .build());

            ToolResultMessage result = toolRegistry.execute(
                    assistantMessage,
                    toolCall,
                    partialResult -> events.add(AgentEvent.builder()
                            .type(AgentEvent.Type.TOOL_EXECUTION_UPDATE)
                            .turn(turn)
                            .toolCall(toolCall)
                            .partialToolResult(partialResult)
                            .build())
            );
            toolResults.add(result);

            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_EXECUTION_END)
                    .turn(turn)
                    .toolCall(toolCall)
                    .toolResult(result)
                    .text(MessageContents.text(result))
                    .build());
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_RESULT)
                    .turn(turn)
                    .toolResult(result)
                    .text(MessageContents.text(result))
                    .build());
        }

        return new ToolCallStep(toolResults, events);
    }

    private record ToolCallStep(
            List<ToolResultMessage> toolResults,
            List<AgentEvent> events
    ) {
    }
}
