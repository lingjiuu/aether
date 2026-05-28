package io.github.lingjiuu.provider;

import io.github.lingjiuu.model.client.ModelRetryOptions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderEndpoint(
        String providerId,
        String wireApi,
        String baseUrl,
        Map<String, String> headers,
        ModelRetryOptions retryOptions
) {

    public ProviderEndpoint {
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        retryOptions = retryOptions == null ? ModelRetryOptions.defaults() : retryOptions;
    }
}
