package io.github.lingjiuu.llm;

import java.util.List;

public record ModelOption(
        String providerId,
        String modelId,
        String name,
        String api,
        Long contextWindowTokens,
        Long autoCompactTokenLimit,
        List<String> input
) {

    public ModelOption {
        input = input == null ? List.of() : List.copyOf(input);
    }
}
