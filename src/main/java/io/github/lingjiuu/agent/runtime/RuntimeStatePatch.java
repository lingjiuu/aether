package io.github.lingjiuu.agent.runtime;

import io.github.lingjiuu.message.Message;

import java.util.List;

public record RuntimeStatePatch(
        List<Message> replacementMessages,
        List<Message> recordedMessages
) {

    public RuntimeStatePatch {
        replacementMessages = replacementMessages == null ? null : List.copyOf(replacementMessages);
        recordedMessages = recordedMessages == null ? List.of() : List.copyOf(recordedMessages);
    }

    public static RuntimeStatePatch none() {
        return new RuntimeStatePatch(null, List.of());
    }

    public static RuntimeStatePatch replaceActiveMessages(
            List<Message> replacementMessages,
            List<Message> recordedMessages
    ) {
        return new RuntimeStatePatch(replacementMessages, recordedMessages);
    }

    public boolean hasReplacement() {
        return replacementMessages != null;
    }

    public boolean isEmpty() {
        return !hasReplacement() && recordedMessages.isEmpty();
    }
}
