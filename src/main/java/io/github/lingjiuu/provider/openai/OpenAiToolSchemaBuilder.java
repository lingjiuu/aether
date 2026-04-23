package io.github.lingjiuu.provider.openai;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.Map;

public class OpenAiToolSchemaBuilder {

    public FunctionTool build(ToolDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("tool definition must not be null");
        }

        FunctionTool.Parameters.Builder parametersBuilder = FunctionTool.Parameters.builder();
        Map<String, Object> parametersSchema = definition.parametersSchema();
        if (parametersSchema != null) {
            for (Map.Entry<String, Object> entry : parametersSchema.entrySet()) {
                parametersBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }

        return FunctionTool.builder()
                .name(definition.name())
                .description(definition.description())
                .strict(true)
                .parameters(parametersBuilder.build())
                .build();
    }
}
