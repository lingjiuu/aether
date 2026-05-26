package io.github.lingjiuu.input;

import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.UserMessage;

import java.util.List;

public record ProcessedTurnInput(
        UserMessage userMessage,
        List<ContextMessage> contextMessages
) {

    public ProcessedTurnInput {
        contextMessages = contextMessages == null ? List.of() : List.copyOf(contextMessages);
    }
}
