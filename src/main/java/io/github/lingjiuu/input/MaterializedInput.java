package io.github.lingjiuu.input;

import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.UserMessage;

import java.util.List;

public record MaterializedInput(
        TurnInput turnInput,
        UserMessage userMessage,
        List<ContextMessage> contextMessages
) {

    public MaterializedInput {
        contextMessages = contextMessages == null ? List.of() : List.copyOf(contextMessages);
    }
}
