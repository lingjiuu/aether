package io.github.lingjiuu.agent;

import io.github.lingjiuu.ai.AssistantRequest;
import io.github.lingjiuu.ai.AssistantSampler;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.stream.AssistantStream;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

public class AgentLoop {

    private final AgentConfig config;
    private final AssistantSampler assistantSampler;
    private final ContextTransformer contextTransformer;
    private final LlmMessageConverter llmMessageConverter;
    private final AssistantStreamEventMapper assistantStreamEventMapper;
    private final ToolRegistry toolRegistry;

    public AgentLoop(AgentConfig config, AssistantSampler assistantSampler, ToolRegistry toolRegistry) {
        this(
                config,
                assistantSampler,
                new DefaultContextTransformer(),
                new DefaultLlmMessageConverter(),
                new AssistantStreamEventMapper(),
                toolRegistry
        );
    }

    public AgentLoop(
            AgentConfig config,
            AssistantSampler assistantSampler,
            ContextTransformer contextTransformer,
            LlmMessageConverter llmMessageConverter,
            AssistantStreamEventMapper assistantStreamEventMapper,
            ToolRegistry toolRegistry
    ) {
        this.config = config;
        this.assistantSampler = assistantSampler;
        this.contextTransformer = contextTransformer;
        this.llmMessageConverter = llmMessageConverter;
        this.assistantStreamEventMapper = assistantStreamEventMapper;
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

        SampledAssistantMessage sampledAssistantMessage = sampleAssistantMessage(runtimeState.snapshot(), currentTurn);
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

    private SampledAssistantMessage sampleAssistantMessage(List<Message> messages, int turn) {
        List<AgentEvent> streamEvents = new ArrayList<>();
        AssistantMessage assistantMessage;
        List<Message> messagesForModel = contextTransformer.transformContext(messages);
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
}
