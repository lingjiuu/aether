package io.github.lingjiuu.context;

import io.github.lingjiuu.message.Message;

import java.util.List;

public record ContextProjection(List<Message> messages) {

    public ContextProjection {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
