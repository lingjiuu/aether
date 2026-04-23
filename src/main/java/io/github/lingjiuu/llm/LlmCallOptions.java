package io.github.lingjiuu.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmCallOptions {

    private Double temperature;

    private Integer maxTokens;

    private ReasoningOptions reasoning;
}
