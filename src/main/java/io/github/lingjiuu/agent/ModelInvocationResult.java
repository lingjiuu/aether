package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;

import java.util.List;

public record ModelInvocationResult(
        AssistantMessage assistantMessage,
        String assistantText,
        List<ToolCallContent> toolCalls,
        List<AgentEvent> streamEvents
) {

    public ModelInvocationResult {
        if (assistantMessage == null) {
            throw new IllegalArgumentException("assistantMessage must not be null");
        }
        assistantText = assistantText == null ? "" : assistantText;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        streamEvents = streamEvents == null ? List.of() : List.copyOf(streamEvents);
    }
}
