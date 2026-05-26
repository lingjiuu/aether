package io.github.lingjiuu.provider;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderEndpoint(
        String providerId,
        String wireApi,
        String baseUrl,
        Map<String, String> headers
) {

    public ProviderEndpoint {
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
    }
}
