package io.github.lingjiuu.compact;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;

import java.util.List;

public class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    public long estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long chars = 0;
        for (Message message : messages) {
            chars += estimateChars(message);
        }
        return Math.max(1, Math.ceilDiv(chars, CHARS_PER_TOKEN));
    }

    public long estimateChars(Message message) {
        if (message == null) {
            return 0;
        }
        return MessageContents.text(message).length();
    }
}
