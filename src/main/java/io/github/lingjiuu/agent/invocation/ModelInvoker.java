package io.github.lingjiuu.agent.invocation;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.runtime.AgentRunOptions;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.content.TextContent;

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
        return invoke(request, turn, AgentRunOptions.defaults());
    }

    public ModelInvocationResult invoke(LlmRequest request, int turn, AgentRunOptions options) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        AgentRunOptions safeOptions = options == null ? AgentRunOptions.defaults() : options;
        if (safeOptions.cancellationToken().isCancellationRequested()) {
            return abortedResult(List.of());
        }

        List<AgentEvent> streamEvents = new ArrayList<>();
        AssistantMessage assistantMessage;
        try (AssistantStream streaming = llmClient.stream(request)) {
            AutoCloseable cancelRegistration = safeOptions.cancellationToken().onCancel(() -> closeQuietly(streaming));
            try {
                assistantMessage = streaming.consume(
                        event -> streamEvents.addAll(assistantStreamEventMapper.map(event, turn))
                );
            } finally {
                closeQuietly(cancelRegistration);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to close assistant stream", e);
        }
        if (safeOptions.cancellationToken().isCancellationRequested()) {
            return abortedResult(streamEvents);
        }

        return new ModelInvocationResult(
                assistantMessage,
                MessageContents.text(assistantMessage),
                MessageContents.toolCalls(assistantMessage),
                streamEvents
        );
    }

    private ModelInvocationResult abortedResult(List<AgentEvent> streamEvents) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .stopReason(AssistantMessage.StopReason.ABORTED)
                .contents(List.of(TextContent.builder().text("").build()))
                .build();
        return new ModelInvocationResult(
                assistantMessage,
                "",
                List.of(),
                streamEvents
        );
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }
}
