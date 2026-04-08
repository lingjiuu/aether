package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DefaultContextTransformer implements ContextTransformer {

    private final List<ContextTransformer> transformers;

    public DefaultContextTransformer() {
        this(List.of(
                new ContextWindowTransformer(),
                new ContextEnricher(),
                new TokenBudgetTransformer()
        ));
    }

    public DefaultContextTransformer(List<ContextTransformer> transformers) {
        if (transformers == null) {
            throw new IllegalArgumentException("transformers must not be null");
        }
        this.transformers = List.copyOf(transformers);
    }

    @Override
    public List<Message> transformContext(List<Message> messages) {
        List<Message> current = normalize(messages);
        for (ContextTransformer transformer : transformers) {
            if (transformer == null) {
                continue;
            }
            current = normalize(transformer.transformContext(current));
        }
        return current;
    }

    private List<Message> normalize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
