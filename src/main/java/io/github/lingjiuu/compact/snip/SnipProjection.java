package io.github.lingjiuu.compact.snip;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.SystemMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SnipProjection {

    public List<Message> apply(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Set<String> removedIds = new LinkedHashSet<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage systemMessage
                    && systemMessage.getSubtype() == SystemMessage.Subtype.SNIP_BOUNDARY) {
                removedIds.addAll(systemMessage.getRemovedMessageIds());
            }
        }
        if (removedIds.isEmpty()) {
            return List.copyOf(messages);
        }
        List<Message> projected = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                projected.add(message);
                continue;
            }
            if (!removedIds.contains(message.id())) {
                projected.add(message);
            }
        }
        return List.copyOf(projected);
    }
}
