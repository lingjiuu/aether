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

public class OpenAiProvider implements Provider {

    private final OpenAiRequestBuilder requestBuilder;
    private final OpenAiStreamParser streamParser;

    public OpenAiProvider() {
        this(new OpenAiRequestBuilder(), new OpenAiStreamParser());
    }

    public OpenAiProvider(OpenAiRequestBuilder requestBuilder, OpenAiStreamParser streamParser) {
        this.requestBuilder = requestBuilder;
        this.streamParser = streamParser;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public AssistantStream stream(LlmRequest request) {
        if (request == null || request.getModel() == null) {
            throw new IllegalArgumentException("request model must not be null");
        }

        LlmModel model = request.getModel();
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
