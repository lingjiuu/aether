package io.github.lingjiuu.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();
}
