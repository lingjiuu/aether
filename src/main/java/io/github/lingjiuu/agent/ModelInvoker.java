package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ModelInvoker {

    private final LlmClient llmClient;
    private final AssistantStreamEventMapper assistantStreamEventMapper;

    public ModelInvoker(LlmClient llmClient, AssistantStreamEventMapper assistantStreamEventMapper) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient must not be null");
        }
        if (assistantStreamEventMapper == null) {
            throw new IllegalArgumentException("assistantStreamEventMapper must not be null");
        }
        this.llmClient = llmClient;
        this.assistantStreamEventMapper = assistantStreamEventMapper;
    }

    public ModelInvocationResult invoke(LlmRequest request, int turn) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }

        List<AgentEvent> streamEvents = new ArrayList<>();
        AssistantMessage assistantMessage;
        try (AssistantStream streaming = llmClient.stream(request)) {
            assistantMessage = streaming.consume(
                    event -> streamEvents.addAll(assistantStreamEventMapper.map(event, turn))
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to close assistant stream", e);
        }

        return new ModelInvocationResult(
                assistantMessage,
                MessageContents.text(assistantMessage),
                MessageContents.toolCalls(assistantMessage),
                streamEvents
        );
    }
}
