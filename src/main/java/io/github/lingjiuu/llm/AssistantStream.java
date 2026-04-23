package io.github.lingjiuu.llm;

import io.github.lingjiuu.message.AssistantMessage;

import java.io.IOException;
import java.util.function.Consumer;

public abstract class AssistantStream implements AutoCloseable {

    public abstract AssistantMessage consume(Consumer<AssistantStreamEvent> consumer);

    public abstract AssistantMessage result();

    @Override
    public void close() throws IOException {
    }
}
