package io.github.lingjiuu.agent.turn.pipeline;

import io.github.lingjiuu.message.Message;

import java.util.List;

public record PreModelStepResult(
        List<Message> messages,
        List<Message> recordedMessages
) {

    public PreModelStepResult {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        messages = List.copyOf(messages);
        recordedMessages = recordedMessages == null ? List.of() : List.copyOf(recordedMessages);
    }

    public static PreModelStepResult unchanged(List<Message> messages) {
        return new PreModelStepResult(messages, List.of());
    }
}
