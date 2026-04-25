package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;

import java.util.List;

public record TranscriptProjection(
        List<Message> messages
) {

    public TranscriptProjection {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
