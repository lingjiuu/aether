package io.github.lingjiuu.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderSession;
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
    public ProviderSession openSession(LlmModel model, RequestAuth auth) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        return new OpenAiProviderSession(
                createClient(model, auth),
                requestBuilder,
                streamParser
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
