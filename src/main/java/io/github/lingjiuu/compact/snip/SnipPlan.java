package io.github.lingjiuu.compact.snip;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.SystemMessage;

import java.util.List;

public record SnipPlan(
        List<Message> messages,
        SystemMessage boundaryMessage,
        long tokensFreed
) {

    public SnipPlan {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        messages = List.copyOf(messages);
    }

    public boolean executed() {
        return boundaryMessage != null;
    }
}
