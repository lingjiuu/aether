package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AgentRuntimeState {

    private final List<Message> messages = new ArrayList<>();
    private int currentTurn = 1;
    private boolean terminal;
    private TerminationReason terminationReason;

    public AgentRuntimeState() {
    }

    public AgentRuntimeState(List<Message> messages) {
        appendAll(messages);
    }

    public List<Message> snapshot() {
        return List.copyOf(messages);
    }

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

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int size() {
        return messages.size();
    }

    public Message lastMessage() {
        return messages.isEmpty() ? null : messages.getLast();
    }

    public int currentTurn() {
        return currentTurn;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public TerminationReason terminationReason() {
        return terminationReason;
    }

    public void advanceTurn() {
        currentTurn++;
    }

    public void finish(TerminationReason terminationReason) {
        if (terminationReason == null) {
            throw new IllegalArgumentException("terminationReason must not be null");
        }
        terminal = true;
        this.terminationReason = terminationReason;
    }

    public enum TerminationReason {
        COMPLETED,
        FAILED,
        ABORTED
    }
}
