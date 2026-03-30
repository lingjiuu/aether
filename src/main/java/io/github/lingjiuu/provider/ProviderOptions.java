package io.github.lingjiuu.provider;

import io.github.lingjiuu.model.Reasoning;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderOptions {

    private String apiKey;

    private Map<String, String> headers;

    private Double temperature;

    private Integer maxTokens;

    private Reasoning reasoning;
}
