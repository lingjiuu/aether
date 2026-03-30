package io.github.lingjiuu.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedRequestAuth {

    private boolean ok;

    private String apiKey;

    private Map<String, String> headers;

    private String error;

    public static ResolvedRequestAuth ok(String apiKey, Map<String, String> headers) {
        return ResolvedRequestAuth.builder()
                .ok(true)
                .apiKey(apiKey)
                .headers(headers)
                .build();
    }

    public static ResolvedRequestAuth error(String error) {
        return ResolvedRequestAuth.builder()
                .ok(false)
                .error(error)
                .build();
    }
}
