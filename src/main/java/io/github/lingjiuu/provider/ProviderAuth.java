package io.github.lingjiuu.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderAuth {

    private boolean ok;

    private String apiKey;

    private Map<String, String> headers;

    private String error;

    public static ProviderAuth ok(String apiKey, Map<String, String> headers) {
        return ProviderAuth.builder()
                .ok(true)
                .apiKey(apiKey)
                .headers(headers)
                .build();
    }

    public static ProviderAuth error(String error) {
        return ProviderAuth.builder()
                .ok(false)
                .error(error)
                .build();
    }
}
