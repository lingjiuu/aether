package io.github.lingjiuu.wire;

import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.tool.ToolCancellationToken;

public interface WireSession extends AutoCloseable {

    AssistantStream stream(ModelRequest request, ToolCancellationToken cancellationToken);

    @Override
    default void close() {
    }
}
