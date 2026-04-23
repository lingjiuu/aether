package io.github.lingjiuu.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.ResponseCreateParams;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.RequestAuth;

import java.util.LinkedHashMap;
import java.util.Map;

public class OpenAiResponsesProvider implements Provider {

    private final OpenAiResponsesRequestBuilder requestBuilder;
    private final OpenAiResponsesStreamParser streamParser;

    public OpenAiResponsesProvider() {
        this(new OpenAiResponsesRequestBuilder(), new OpenAiResponsesStreamParser());
    }

    public OpenAiResponsesProvider(OpenAiResponsesRequestBuilder requestBuilder, OpenAiResponsesStreamParser streamParser) {
        this.requestBuilder = requestBuilder;
        this.streamParser = streamParser;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public AssistantStream stream(LlmRequest request) {
        if (request == null || request.getConfig() == null || request.getConfig().getModel() == null) {
            throw new IllegalArgumentException("request config and model must not be null");
        }

        LlmModel model = request.getConfig().getModel();
        OpenAIClient client = createClient(model, request.getAuth());
        ResponseCreateParams params = requestBuilder.buildRequest(request);
        return streamParser.parseStream(
                client.responses().createStreaming(params),
                model.getId(),
                model.getProvider()
        );
    }

    private OpenAIClient createClient(LlmModel model, RequestAuth auth) {
        String apiKey = auth == null ? null : auth.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("No API key for provider: " + model.getProvider());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (auth != null && auth.getHeaders() != null) {
            headers.putAll(auth.getHeaders());
        }

        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(model.getBaseUrl());
        if (!headers.isEmpty()) {
            headers.forEach(builder::putHeader);
        }
        return builder.build();
    }
}
