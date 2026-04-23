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
public class RequestAuth {

    private boolean ok;

    private String apiKey;

    private Map<String, String> headers;

    private String error;

    public static RequestAuth ok(String apiKey, Map<String, String> headers) {
        return RequestAuth.builder()
                .ok(true)
                .apiKey(apiKey)
                .headers(headers)
                .build();
    }

    public static RequestAuth error(String error) {
        return RequestAuth.builder()
                .ok(false)
                .error(error)
                .build();
    }
}
