package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContextEnricher implements ContextTransformer {

    @Override
    public List<Message> transformContext(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
