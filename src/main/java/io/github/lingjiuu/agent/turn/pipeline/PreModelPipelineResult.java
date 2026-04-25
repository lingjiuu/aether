package io.github.lingjiuu.agent.turn.pipeline;

import io.github.lingjiuu.message.Message;

import java.util.List;

public record PreModelPipelineResult(
        List<Message> messages,
        List<Message> recordedMessages
) {

    public PreModelPipelineResult {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        messages = List.copyOf(messages);
        recordedMessages = recordedMessages == null ? List.of() : List.copyOf(recordedMessages);
    }
}
