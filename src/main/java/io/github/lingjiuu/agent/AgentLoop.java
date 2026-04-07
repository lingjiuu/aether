package io.github.lingjiuu.agent;

import io.github.lingjiuu.ai.AiStreams;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.ai.ResolvedRequestAuth;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.AgentState;
import io.github.lingjiuu.provider.AssistantMessageEvent;
import io.github.lingjiuu.provider.AssistantMessageEventStream;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolUpdateCallback;

import java.util.ArrayList;
import java.util.List;

public class AgentLoop {

    private final AgentState state;
    private final AiStreams aiStreams;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;

    public AgentLoop(AgentState state, AiStreams aiStreams, ModelRegistry modelRegistry, ToolRegistry toolRegistry) {
        this.state = state;
        this.aiStreams = aiStreams;
        this.modelRegistry = modelRegistry;
        this.toolRegistry = toolRegistry;
    }

    public void run(AgentEventListener listener) {
        if (state.getMessages().isEmpty()) {
            throw new IllegalStateException("Cannot run agent loop without any messages in session.");
        }
        int turn = 1;

        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.RUN_START)
                .build());

        while (true) {
            int currentTurn = turn;
            emit(listener, AgentEvent.builder()
                    .type(AgentEvent.Type.TURN_START)
                    .turn(currentTurn)
                    .build());

            ResolvedRequestAuth auth = modelRegistry.getApiKeyAndHeaders(state.getModel());
            if (!auth.isOk()) {
                throw new IllegalStateException(auth.getError());
            }

            ProviderOptions options = ProviderOptions.builder()
                    .apiKey(auth.getApiKey())
                    .headers(auth.getHeaders())
                    .reasoning(state.getReasoning())
                    .build();

            List<ToolResultMessage> toolResults = new ArrayList<>();
            StringBuilder streamedText = new StringBuilder();
            StringBuilder reasoningSummaryText = new StringBuilder();
            AssistantMessage assistantMessage;

            try (AssistantMessageEventStream streaming = aiStreams.streamSimple(state, options)) {
                assistantMessage = streaming.consume(event -> handleAssistantEvent(event, streamedText, reasoningSummaryText, currentTurn, listener));
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to close assistant stream", e);
            }

            String assistantText = MessageContents.text(assistantMessage);
            String assistantThinking = MessageContents.thinking(assistantMessage);
            List<ToolCallContent> toolCalls = MessageContents.toolCalls(assistantMessage);
            boolean shouldContinue = !toolCalls.isEmpty();

            state.getMessages().add(assistantMessage);
            emit(listener, AgentEvent.builder()
                    .type(AgentEvent.Type.ASSISTANT_MESSAGE)
                    .turn(currentTurn)
                    .assistantMessage(assistantMessage)
                    .text(assistantText)
                    .build());

            for (ToolCallContent toolCall : toolCalls) {
                emit(listener, AgentEvent.builder()
                        .type(AgentEvent.Type.TOOL_CALL)
                        .turn(currentTurn)
                        .toolCall(toolCall)
                        .build());
                toolResults.add(executeToolCall(assistantMessage, toolCall, currentTurn, listener));
            }

            for (ToolResultMessage toolResult : toolResults) {
                state.getMessages().add(toolResult);
                emit(listener, AgentEvent.builder()
                        .type(AgentEvent.Type.TOOL_RESULT)
                        .turn(currentTurn)
                        .toolResult(toolResult)
                        .text(MessageContents.text(toolResult))
                        .build());
            }

            if (!shouldContinue) {
                emit(listener, AgentEvent.builder()
                        .type(AgentEvent.Type.FINAL_ANSWER)
                        .turn(currentTurn)
                        .assistantMessage(assistantMessage)
                        .text(assistantText)
                        .build());
                break;
            }

            turn++;
        }

        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.RUN_END)
                .turn(turn)
                .build());
    }

    private ToolResultMessage executeToolCall(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            int turn,
            AgentEventListener listener
    ) {
        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.TOOL_EXECUTION_START)
                .turn(turn)
                .toolCall(toolCall)
                .build());
        ToolResultMessage result = toolRegistry.execute(assistantMessage, toolCall, partialResult -> emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.TOOL_EXECUTION_UPDATE)
                .turn(turn)
                .toolCall(toolCall)
                .partialToolResult(partialResult)
                .build()));
        emit(listener, AgentEvent.builder()
                .type(AgentEvent.Type.TOOL_EXECUTION_END)
                .turn(turn)
                .toolCall(toolCall)
                .toolResult(result)
                .text(MessageContents.text(result))
                .build());
        return result;
    }

    private void handleAssistantEvent(
            AssistantMessageEvent event,
            StringBuilder streamedText,
            StringBuilder reasoningSummaryText,
            int turn,
            AgentEventListener listener
    ) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case TEXT_DELTA -> {
                streamedText.append(event.getDelta());
                emit(listener, AgentEvent.builder()
                        .type(AgentEvent.Type.ASSISTANT_TEXT_DELTA)
                        .turn(turn)
                        .delta(event.getDelta())
                        .build());
            }
            case THINKING_DELTA -> {
                reasoningSummaryText.append(event.getDelta());
                emit(listener, AgentEvent.builder()
                        .type(AgentEvent.Type.REASONING_DELTA)
                        .turn(turn)
                        .delta(event.getDelta())
                        .build());
            }
            default -> {
            }
        }
    }

    private void emit(AgentEventListener listener, AgentEvent event) {
        if (listener != null && event != null) {
            listener.onEvent(event);
        }
    }
}
