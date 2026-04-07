package io.github.lingjiuu.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class ToolArgumentValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    public Map<String, Object> validate(ToolDefinition definition, String argumentsJson) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            Map<String, Object> arguments = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });

            JsonNode parametersNode = definition.schema() != null && definition.schema().parameters().isPresent()
                    ? OBJECT_MAPPER.valueToTree(definition.schema().parameters().get())
                    : null;

            if (parametersNode != null && parametersNode.has("required") && parametersNode.get("required").isArray()) {
                List<String> requiredFields = OBJECT_MAPPER.convertValue(parametersNode.get("required"), new TypeReference<>() {
                });
                for (String field : requiredFields) {
                    if (!arguments.containsKey(field) || arguments.get(field) == null) {
                        throw new IllegalArgumentException("Missing required tool argument: " + field);
                    }
                }
            }

            return arguments;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tool arguments JSON: " + e.getMessage(), e);
        }
    }
}
