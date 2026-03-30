package io.github.lingjiuu.provider;

import io.github.lingjiuu.message.AssistantMessage;

import java.io.IOException;
import java.util.function.Consumer;

public abstract class AssistantMessageEventStream implements AutoCloseable {

    public abstract AssistantMessage consume(Consumer<AssistantMessageEvent> consumer);

    public abstract AssistantMessage result();

    @Override
    public void close() throws IOException {
    }
}
