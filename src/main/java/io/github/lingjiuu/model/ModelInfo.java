package io.github.lingjiuu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfo {

    private String id;

    private String name;

    private Long contextWindowTokens;

    private Long autoCompactTokenLimit;

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
