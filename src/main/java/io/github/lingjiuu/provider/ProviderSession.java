package io.github.lingjiuu.provider;

import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.tool.ToolCancellationToken;

public interface ProviderSession extends AutoCloseable {

    AssistantStream stream(LlmRequest request, ToolCancellationToken cancellationToken);

    @Override
    default void close() {
    }
}
