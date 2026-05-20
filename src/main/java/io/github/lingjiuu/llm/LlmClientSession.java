package io.github.lingjiuu.llm;

import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolCancellationToken;

public class LlmClientSession implements AutoCloseable {

    private final ProviderSession providerSession;
    private final LlmModel model;
    private final RequestAuth auth;

    LlmClientSession(ProviderSession providerSession, LlmModel model, RequestAuth auth) {
        if (providerSession == null) {
            throw new IllegalArgumentException("providerSession must not be null");
        }
        this.providerSession = providerSession;
        this.model = model;
        this.auth = auth;
    }

    public AssistantStream stream(LlmRequest request, ToolCancellationToken cancellationToken) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        LlmRequest normalizedRequest = request.toBuilder()
                .model(request.getModel() == null ? model : request.getModel())
                .auth(request.getAuth() == null ? auth : request.getAuth())
                .callOptions(request.getCallOptions() == null ? LlmCallOptions.builder().build() : request.getCallOptions())
                .build();
        return providerSession.stream(
                normalizedRequest,
                cancellationToken == null ? ToolCancellationToken.none() : cancellationToken
        );
    }

    @Override
    public void close() {
        providerSession.close();
    }
}
