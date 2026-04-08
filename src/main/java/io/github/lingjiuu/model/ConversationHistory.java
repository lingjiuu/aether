package io.github.lingjiuu.model;

import io.github.lingjiuu.message.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ConversationHistory {

    private final List<Message> messages = new ArrayList<>();

    public void append(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(message);
    }

    public void appendAll(Collection<? extends Message> newMessages) {
        if (newMessages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        for (Message message : newMessages) {
            append(message);
        }
    }

    public List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int size() {
        return messages.size();
    }

    public Message lastMessage() {
        return messages.isEmpty() ? null : messages.getLast();
    }

    public void clear() {
        messages.clear();
    }
}
