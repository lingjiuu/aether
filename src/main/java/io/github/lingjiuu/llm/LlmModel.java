package io.github.lingjiuu.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModel {

    private String id;

    private String name;

    private String api;

    private String provider;

    private String baseUrl;

    private Long contextWindowTokens;

    private Long autoCompactTokenLimit;

    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();

    @Builder.Default
    private List<String> input = List.of("text");

    public Long resolvedAutoCompactTokenLimit() {
        Long contextLimit = contextWindowTokens == null ? null : (contextWindowTokens * 9) / 10;
        if (contextLimit == null) {
            return autoCompactTokenLimit;
        }
        if (autoCompactTokenLimit == null) {
            return contextLimit;
        }
        return Math.min(autoCompactTokenLimit, contextLimit);
    }
}
