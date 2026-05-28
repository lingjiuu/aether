package io.github.lingjiuu.wire.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.client.ModelErrorInfo;
import io.github.lingjiuu.model.client.ModelInvocationException;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.provider.ProviderEndpoint;
import io.github.lingjiuu.wire.WireSession;
import io.github.lingjiuu.tool.ToolCancellationToken;

import java.util.concurrent.CancellationException;

public class OpenAiResponsesSession implements WireSession {

    private final ModelInfo model;
    private final ProviderEndpoint endpoint;
    private final OpenAIClient client;
    private final OpenAiRequestMapper requestMapper;

    OpenAiResponsesSession(
            ModelInfo model,
            ProviderEndpoint endpoint,
            OpenAIClient client,
            OpenAiRequestMapper requestMapper
    ) {
        this.model = model;
        this.endpoint = endpoint;
        this.client = client;
        this.requestMapper = requestMapper;
    }

    @Override
    public AssistantStream stream(ModelRequest request, ToolCancellationToken cancellationToken) {
        ToolCancellationToken token = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            throw new CancellationException("Model request was cancelled.");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }

        ResponseCreateParams params = requestMapper.buildRequest(request, model);
        StreamResponse<ResponseStreamEvent> streamResponse = null;
        try {
            streamResponse = client.responses().createStreaming(params);
            if (token.isCancellationRequested()) {
                closeQuietly(streamResponse);
                throw new CancellationException("Model request was cancelled.");
            }
            return new OpenAiResponsesStream(streamResponse, model.getId(), endpoint.providerId());
        } catch (OpenAIException e) {
            if (token.isCancellationRequested()) {
                closeQuietly(streamResponse);
                throw new CancellationException("Model request was cancelled.");
            }
            throw new ModelInvocationException(ModelErrorInfo.fromOpenAiException(e), e);
        }
    }

    private void closeQuietly(StreamResponse<ResponseStreamEvent> streamResponse) {
        if (streamResponse == null) {
            return;
        }
        streamResponse.close();
    }
}
