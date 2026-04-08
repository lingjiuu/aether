package io.github.lingjiuu.agent;

import io.github.lingjiuu.ai.AssistantRequest;
import io.github.lingjiuu.ai.AssistantSampler;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.model.ConversationHistory;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.stream.AssistantStream;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

public class AgentLoop {

    private final AgentConfig config;
    private final ConversationHistory history;
    private final AssistantSampler assistantSampler;
    private final ContextTransformer contextTransformer;
    private final LlmMessageConverter llmMessageConverter;
    private final AssistantStreamEventMapper assistantStreamEventMapper;
    private final ToolRegistry toolRegistry;

    public AgentLoop(AgentConfig config, ConversationHistory history, AssistantSampler assistantSampler, ToolRegistry toolRegistry) {
        this(
                config,
                history,
                assistantSampler,
                new DefaultContextTransformer(),
                new DefaultLlmMessageConverter(),
                new AssistantStreamEventMapper(),
                toolRegistry
        );
    }

    public AgentLoop(
            AgentConfig config,
            ConversationHistory history,
            AssistantSampler assistantSampler,
            ContextTransformer contextTransformer,
            LlmMessageConverter llmMessageConverter,
            AssistantStreamEventMapper assistantStreamEventMapper,
            ToolRegistry toolRegistry
    ) {
        this.config = config;
        this.history = history;
        this.assistantSampler = assistantSampler;
        this.contextTransformer = contextTransformer;
        this.llmMessageConverter = llmMessageConverter;
        this.assistantStreamEventMapper = assistantStreamEventMapper;
        this.toolRegistry = toolRegistry;
    }

    public void run(AgentEventListener listener) {
        if (history.isEmpty()) {
            throw new IllegalStateException("Cannot run agent loop without any messages in session.");
        }
        RunState runState = RunState.start();

        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.RUN_START)
                .build());

        while (!runState.isTerminal()) {
            StepResult stepResult = step(runState);
            applyStepResult(runState, stepResult, listener);
        }

        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.RUN_END)
                .turn(runState.currentTurn())
                .build());
    }

    private StepResult step(RunState runState) {
        int currentTurn = runState.currentTurn();
        List<Message> appendedMessages = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();

        events.add(AgentEvent.builder()
                .type(AgentEvent.Type.TURN_START)
                .turn(currentTurn)
                .build());

        SampledAssistantMessage sampledAssistantMessage = sampleAssistantMessage(currentTurn);
        appendedMessages.add(sampledAssistantMessage.assistantMessage());
        events.addAll(sampledAssistantMessage.streamEvents());
        events.add(AgentEvent.builder()
                .type(AgentEvent.Type.ASSISTANT_MESSAGE)
                .turn(currentTurn)
                .assistantMessage(sampledAssistantMessage.assistantMessage())
                .text(sampledAssistantMessage.assistantText())
                .build());

        if (sampledAssistantMessage.assistantMessage().getStopReason() == AssistantMessage.StopReason.ERROR) {
            return new StepResult(
                    appendedMessages,
                    events,
                    StepTransition.FAILED,
                    TerminationReason.FAILED
            );
        }
        if (sampledAssistantMessage.assistantMessage().getStopReason() == AssistantMessage.StopReason.ABORTED) {
            return new StepResult(
                    appendedMessages,
                    events,
                    StepTransition.ABORTED,
                    TerminationReason.ABORTED
            );
        }

        if (sampledAssistantMessage.toolCalls().isEmpty()) {
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.FINAL_ANSWER)
                    .turn(currentTurn)
                    .assistantMessage(sampledAssistantMessage.assistantMessage())
                    .text(sampledAssistantMessage.assistantText())
                    .build());
            return new StepResult(
                    appendedMessages,
                    events,
                    StepTransition.COMPLETE,
                    TerminationReason.COMPLETED
            );
        }

        ToolCallStep toolCallStep = executeToolCalls(
                sampledAssistantMessage.assistantMessage(),
                sampledAssistantMessage.toolCalls(),
                currentTurn
        );
        appendedMessages.addAll(toolCallStep.toolResults());
        events.addAll(toolCallStep.events());

        return new StepResult(
                appendedMessages,
                events,
                StepTransition.NEXT_TURN,
                null
        );
    }

    private SampledAssistantMessage sampleAssistantMessage(int turn) {
        List<AgentEvent> streamEvents = new ArrayList<>();
        AssistantMessage assistantMessage;
        List<Message> messagesForModel = contextTransformer.transformContext(history.snapshot());
        List<Message> llmMessages = llmMessageConverter.convertToLlm(messagesForModel);

        AssistantRequest request = AssistantRequest.builder()
                .config(config)
                .messages(llmMessages)
                .options(ProviderOptions.builder().build())
                .build();

        try (AssistantStream streaming = assistantSampler.stream(request)) {
            assistantMessage = streaming.consume(event -> streamEvents.addAll(assistantStreamEventMapper.map(event, turn)));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to close assistant stream", e);
        }

        return new SampledAssistantMessage(
                assistantMessage,
                MessageContents.text(assistantMessage),
                MessageContents.toolCalls(assistantMessage),
                streamEvents
        );
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

    private void applyStepResult(
            RunState runState,
            StepResult stepResult,
            AgentEventListener listener
    ) {
        int appendedMessageIndex = 0;
        for (AgentEvent event : stepResult.events()) {
            if (
                    event.getType() == AgentEvent.Type.ASSISTANT_MESSAGE
                            || event.getType() == AgentEvent.Type.TOOL_RESULT
            ) {
                if (appendedMessageIndex >= stepResult.appendedMessages().size()) {
                    throw new IllegalStateException("No pending message available for event: " + event.getType());
                }
                history.append(stepResult.appendedMessages().get(appendedMessageIndex++));
            }
            emit(listener, event);
        }
        if (appendedMessageIndex != stepResult.appendedMessages().size()) {
            throw new IllegalStateException("Unapplied messages remain after applyStepResult.");
        }

        if (stepResult.transition() == StepTransition.NEXT_TURN) {
            runState.advanceTurn();
            return;
        }

        runState.finish(stepResult.terminationReason());
    }

    private void emit(AgentEventListener listener, AgentEvent event) {
        if (listener != null && event != null) {
            listener.onEvent(event);
        }
    }

    private enum StepTransition {
        NEXT_TURN,
        COMPLETE,
        FAILED,
        ABORTED
    }

    private enum TerminationReason {
        COMPLETED,
        FAILED,
        ABORTED,
        MAX_TURNS
    }

    private record StepResult(
            List<Message> appendedMessages,
            List<AgentEvent> events,
            StepTransition transition,
            TerminationReason terminationReason
    ) {
    }

    private record SampledAssistantMessage(
            AssistantMessage assistantMessage,
            String assistantText,
            List<ToolCallContent> toolCalls,
            List<AgentEvent> streamEvents
    ) {
    }

    private record ToolCallStep(
            List<ToolResultMessage> toolResults,
            List<AgentEvent> events
    ) {
    }

    private static final class RunState {
        private int currentTurn;
        private boolean terminal;
        private TerminationReason terminationReason;

        private RunState(int currentTurn) {
            this.currentTurn = currentTurn;
        }

        private static RunState start() {
            return new RunState(1);
        }

        private int currentTurn() {
            return currentTurn;
        }

        private boolean isTerminal() {
            return terminal;
        }

        private void advanceTurn() {
            currentTurn++;
        }

        private void finish(TerminationReason terminationReason) {
            this.terminal = true;
            this.terminationReason = terminationReason;
        }
    }
}
