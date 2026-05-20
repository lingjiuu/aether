package io.github.lingjiuu.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.tool.ToolCancellationToken;

import java.util.concurrent.CancellationException;

public class OpenAiProviderSession implements ProviderSession {

    private final OpenAIClient client;
    private final OpenAiRequestBuilder requestBuilder;
    private final OpenAiStreamParser streamParser;

    OpenAiProviderSession(
            OpenAIClient client,
            OpenAiRequestBuilder requestBuilder,
            OpenAiStreamParser streamParser
    ) {
        this.client = client;
        this.requestBuilder = requestBuilder;
        this.streamParser = streamParser;
    }

    @Override
    public AssistantStream stream(LlmRequest request, ToolCancellationToken cancellationToken) {
        ToolCancellationToken token = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            throw new CancellationException("Model request was cancelled.");
        }
        if (request == null || request.getModel() == null) {
            throw new IllegalArgumentException("request model must not be null");
        }

        LlmModel model = request.getModel();
        ResponseCreateParams params = requestBuilder.buildRequest(request);
        StreamResponse<ResponseStreamEvent> streamResponse = null;
        try {
            streamResponse = client.responses().createStreaming(params);
            if (token.isCancellationRequested()) {
                closeQuietly(streamResponse);
                throw new CancellationException("Model request was cancelled.");
            }
            return streamParser.parseStream(
                    streamResponse,
                    model.getId(),
                    model.getProvider()
            );
        } catch (RuntimeException e) {
            if (token.isCancellationRequested()) {
                closeQuietly(streamResponse);
                throw new CancellationException("Model request was cancelled.");
            }
            throw e;
        }
    }

    private void closeQuietly(StreamResponse<ResponseStreamEvent> streamResponse) {
        if (streamResponse == null) {
            return;
        }
        streamResponse.close();
    }
}
