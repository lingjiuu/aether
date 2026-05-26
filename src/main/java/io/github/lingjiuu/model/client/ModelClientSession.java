package io.github.lingjiuu.model.client;

import io.github.lingjiuu.wire.WireSession;
import io.github.lingjiuu.tool.ToolCancellationToken;

public class ModelClientSession implements AutoCloseable {

    private final WireSession wireSession;

    ModelClientSession(WireSession wireSession) {
        if (wireSession == null) {
            throw new IllegalArgumentException("wireSession must not be null");
        }
        this.wireSession = wireSession;
    }

    public AssistantStream stream(ModelRequest request, ToolCancellationToken cancellationToken) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        ModelRequest normalizedRequest = request.toBuilder()
                .callOptions(request.getCallOptions() == null ? ModelCallOptions.builder().build() : request.getCallOptions())
                .build();
        return wireSession.stream(
                normalizedRequest,
                cancellationToken == null ? ToolCancellationToken.none() : cancellationToken
        );
    }

    @Override
    public void close() {
        wireSession.close();
    }
}
