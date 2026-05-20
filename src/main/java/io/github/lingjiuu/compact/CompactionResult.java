package io.github.lingjiuu.compact;

import io.github.lingjiuu.message.Message;

import java.util.List;

public record CompactionResult(
        String summary,
        List<Message> replacementMessages,
        int originalMessageCount,
        int preservedUserMessageCount
) {

    public CompactionResult {
        summary = summary == null ? "" : summary;
        replacementMessages = replacementMessages == null ? List.of() : List.copyOf(replacementMessages);
    }
}
