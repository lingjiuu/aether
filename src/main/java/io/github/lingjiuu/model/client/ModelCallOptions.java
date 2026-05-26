package io.github.lingjiuu.model.client;

import io.github.lingjiuu.model.ReasoningOptions;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCallOptions {

    private Double temperature;

    private Integer maxTokens;

    private ReasoningOptions reasoning;
}
