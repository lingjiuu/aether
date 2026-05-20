package io.github.lingjiuu.context;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ContextManager {

    private final List<Message> messages = new ArrayList<>();
    private final ContextPolicy policy;

    public ContextManager() {
        this(ContextPolicy.defaults(), List.of());
    }

    public ContextManager(ContextPolicy policy, Collection<? extends Message> initialMessages) {
        this.policy = policy == null ? ContextPolicy.defaults() : policy;
        recordAll(initialMessages);
    }

    public synchronized void record(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(normalizeForHistory(message));
    }

    public synchronized void recordAll(Collection<? extends Message> newMessages) {
        if (newMessages == null) {
            return;
        }
        for (Message message : newMessages) {
            record(message);
        }
    }

    public synchronized void replaceAll(Collection<? extends Message> replacementMessages) {
        messages.clear();
        recordAll(replacementMessages);
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized Message lastMessage() {
        return messages.isEmpty() ? null : messages.getLast();
    }

    public ContextProjection normalizeMessagesForModel() {
        return new ContextProjection(snapshot());
    }

    public ContextPolicy policy() {
        return policy;
    }

    private Message normalizeForHistory(Message message) {
        if (!(message instanceof ToolResultMessage toolResultMessage)) {
            return message;
        }

        String text = MessageContents.text(toolResultMessage);
        if (text == null || text.length() <= policy.maxToolResultChars()) {
            return message;
        }

        String truncatedText = text.substring(0, policy.maxToolResultChars())
                + "\n\n[tool result truncated by context policy]";
        List<MessageContent> contents = List.of(TextContent.builder()
                .text(truncatedText)
                .build());
        return ToolResultMessage.builder()
                .id(toolResultMessage.getId())
                .timestamp(toolResultMessage.getTimestamp())
                .toolCallId(toolResultMessage.getToolCallId())
                .toolName(toolResultMessage.getToolName())
                .details(toolResultMessage.getDetails())
                .isError(toolResultMessage.isError())
                .contents(contents)
                .build();
    }
}
