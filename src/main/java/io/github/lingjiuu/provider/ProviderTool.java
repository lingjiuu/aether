package io.github.lingjiuu.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderTool {

    private String name;

    private String description;

    private Boolean strict;

    private JsonNode parameters;
}
