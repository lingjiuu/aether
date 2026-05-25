package io.github.lingjiuu.protocol;

import java.util.List;

public record UiModelInfo(
        String providerId,
        String modelId,
        String name,
        String api,
        Long contextWindowTokens,
        Long autoCompactTokenLimit,
        List<String> input,
        boolean current
) {

    public UiModelInfo {
        input = input == null ? List.of() : List.copyOf(input);
    }
}
