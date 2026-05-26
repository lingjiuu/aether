package io.github.lingjiuu.wire.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;
import io.github.lingjiuu.wire.WireAdapter;
import io.github.lingjiuu.wire.WireSession;

import java.util.LinkedHashMap;
import java.util.Map;

public class OpenAiResponsesAdapter implements WireAdapter {

    private final OpenAiRequestMapper requestMapper;

    public OpenAiResponsesAdapter() {
        this(new OpenAiRequestMapper());
    }

    public OpenAiResponsesAdapter(OpenAiRequestMapper requestMapper) {
        this.requestMapper = requestMapper;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public WireSession openSession(ModelSelection selection) {
        if (selection == null || selection.model() == null || selection.endpoint() == null) {
            throw new IllegalArgumentException("model selection must not be null");
        }
        return new OpenAiResponsesSession(
                selection.model(),
                selection.endpoint(),
                createClient(selection),
                requestMapper
        );
    }

    private OpenAIClient createClient(ModelSelection selection) {
        ProviderEndpoint endpoint = selection.endpoint();
        ProviderAuth auth = selection.auth();
        String apiKey = auth == null ? null : auth.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("No API key for provider: " + endpoint.providerId());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (endpoint.headers() != null) {
            headers.putAll(endpoint.headers());
        }
        if (auth != null && auth.getHeaders() != null) {
            headers.putAll(auth.getHeaders());
        }

        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(endpoint.baseUrl());
        if (!headers.isEmpty()) {
            headers.forEach(builder::putHeader);
        }
        return builder.build();
    }
}
