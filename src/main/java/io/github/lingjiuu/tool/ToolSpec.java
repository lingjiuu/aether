package io.github.lingjiuu.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public record ToolSpec(
        String name,
        String label,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        ToolRiskLevel riskLevel
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    private static final SchemaValidationContext INPUT_CONTEXT = new SchemaValidationContext("tool argument");
    private static final SchemaValidationContext OUTPUT_CONTEXT = new SchemaValidationContext("tool output field");

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        label = label == null || label.isBlank() ? name : label;
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        riskLevel = riskLevel == null ? ToolRiskLevel.UNKNOWN : riskLevel;
    }

    public static ToolSpec of(
            String name,
            String label,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            ToolRiskLevel riskLevel
    ) {
        return new ToolSpec(
                name,
                label,
                description,
                inputSchema,
                outputSchema,
                riskLevel
        );
    }

    public Map<String, Object> validateInputJson(String argumentsJson) {
        return validateInputJson(argumentsJson, null);
    }

    public Map<String, Object> validateInputJson(String argumentsJson, ToolInputPreparer preparer) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            JsonNode argumentsNode = OBJECT_MAPPER.readTree(json);
            Object rawArguments = OBJECT_MAPPER.convertValue(argumentsNode, Object.class);
            Object preparedArguments = preparer == null ? rawArguments : preparer.prepareInput(rawArguments);
            JsonNode preparedNode = OBJECT_MAPPER.valueToTree(preparedArguments);
            if (preparedNode == null || !preparedNode.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }

            JsonNode schemaNode = inputSchema.isEmpty()
                    ? null
                    : OBJECT_MAPPER.valueToTree(inputSchema);
            if (schemaNode != null) {
                validateNode(preparedNode, schemaNode, "", INPUT_CONTEXT);
            }

            return OBJECT_MAPPER.convertValue(preparedNode, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tool arguments JSON: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tool arguments JSON: " + e.getMessage(), e);
        }
    }

    public void validateOutput(Object output) {
        if (outputSchema.isEmpty()) {
            return;
        }
        JsonNode outputNode = OBJECT_MAPPER.valueToTree(output);
        JsonNode schemaNode = OBJECT_MAPPER.valueToTree(outputSchema);
        validateNode(outputNode, schemaNode, "", OUTPUT_CONTEXT);
    }

    private static void validateNode(JsonNode value, JsonNode schema, String path, SchemaValidationContext context) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }

        if (schema.has("oneOf")) {
            validateOneOf(value, schema.get("oneOf"), path, context);
            return;
        }
        if (schema.has("anyOf")) {
            validateAnyOf(value, schema.get("anyOf"), path, context);
            return;
        }
        validateConst(value, schema, path);
        validateType(value, schema, path);
        validateEnum(value, schema, path);

        String type = primaryType(schema);
        if ("object".equals(type) || value.isObject() && schema.has("properties")) {
            validateObject(value, schema, path, context);
        }
        if ("array".equals(type) || value.isArray() && schema.has("items")) {
            validateArray(value, schema, path, context);
        }
        if (value.isNumber()) {
            validateNumber(value, schema, path);
        }
        if (value.isTextual()) {
            validateString(value, schema, path);
        }
    }

    private static void validateOneOf(JsonNode value, JsonNode schemas, String path, SchemaValidationContext context) {
        if (schemas == null || !schemas.isArray()) {
            return;
        }
        int matches = 0;
        IllegalArgumentException lastError = null;
        for (JsonNode candidate : schemas) {
            try {
                validateNode(value, candidate, path, context);
                matches++;
            } catch (IllegalArgumentException e) {
                lastError = e;
            }
        }
        if (matches != 1) {
            String detail = matches == 0 && lastError != null ? ": " + lastError.getMessage() : "";
            throw new IllegalArgumentException(field(path) + " must match exactly one schema" + detail);
        }
    }

    private static void validateAnyOf(JsonNode value, JsonNode schemas, String path, SchemaValidationContext context) {
        if (schemas == null || !schemas.isArray()) {
            return;
        }
        IllegalArgumentException lastError = null;
        for (JsonNode candidate : schemas) {
            try {
                validateNode(value, candidate, path, context);
                return;
            } catch (IllegalArgumentException e) {
                lastError = e;
            }
        }
        String detail = lastError == null ? "" : ": " + lastError.getMessage();
        throw new IllegalArgumentException(field(path) + " must match at least one schema" + detail);
    }

    private static void validateConst(JsonNode value, JsonNode schema, String path) {
        JsonNode constNode = schema.get("const");
        if (constNode != null && !constNode.equals(value)) {
            throw new IllegalArgumentException(field(path) + " must be " + constNode);
        }
    }

    private static void validateType(JsonNode value, JsonNode schema, String path) {
        JsonNode typeNode = schema.get("type");
        if (typeNode == null || typeNode.isNull()) {
            return;
        }

        if (typeNode.isTextual()) {
            String type = typeNode.asText();
            if (!matchesType(value, type)) {
                throw new IllegalArgumentException(field(path) + " must be " + article(type) + typeName(type));
            }
            return;
        }

        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() && matchesType(value, item.asText())) {
                    return;
                }
            }
            throw new IllegalArgumentException(field(path) + " has an invalid type");
        }
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static void validateObject(JsonNode value, JsonNode schema, String path, SchemaValidationContext context) {
        if (!value.isObject()) {
            return;
        }

        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            List<String> requiredFields = OBJECT_MAPPER.convertValue(requiredNode, new TypeReference<>() {
            });
            for (String field : requiredFields) {
                JsonNode child = value.get(field);
                if (child == null) {
                    throw new IllegalArgumentException("Missing required " + context.fieldLabel() + ": " + childPath(path, field));
                }
            }
        }

        JsonNode propertiesNode = schema.get("properties");
        JsonNode additionalPropertiesNode = schema.get("additionalProperties");
        Iterator<String> fields = value.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode child = value.get(field);
            JsonNode propertySchema = propertiesNode == null ? null : propertiesNode.get(field);
            if (propertySchema != null) {
                if (child != null && child.isNull() && (requiredNode == null || !containsRequiredField(requiredNode, field))) {
                    continue;
                }
                validateNode(child, propertySchema, childPath(path, field), context);
                continue;
            }
            if (additionalPropertiesNode != null && additionalPropertiesNode.isBoolean()
                    && !additionalPropertiesNode.asBoolean()) {
                throw new IllegalArgumentException("Unknown " + context.fieldLabel() + ": " + childPath(path, field));
            }
            if (additionalPropertiesNode != null && additionalPropertiesNode.isObject()) {
                validateNode(child, additionalPropertiesNode, childPath(path, field), context);
            }
        }
    }

    private static boolean containsRequiredField(JsonNode requiredNode, String field) {
        if (requiredNode == null || !requiredNode.isArray()) {
            return false;
        }
        for (JsonNode item : requiredNode) {
            if (item.isTextual() && field.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static void validateArray(JsonNode value, JsonNode schema, String path, SchemaValidationContext context) {
        if (!value.isArray()) {
            return;
        }
        JsonNode itemsNode = schema.get("items");
        if (itemsNode == null || !itemsNode.isObject()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateNode(value.get(i), itemsNode, path + "[" + i + "]", context);
        }
    }

    private static void validateEnum(JsonNode value, JsonNode schema, String path) {
        JsonNode enumNode = schema.get("enum");
        if (enumNode == null || !enumNode.isArray()) {
            return;
        }
        for (JsonNode allowed : enumNode) {
            if (allowed.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException(field(path) + " must be one of " + enumNode);
    }

    private static void validateNumber(JsonNode value, JsonNode schema, String path) {
        JsonNode minimum = schema.get("minimum");
        if (minimum != null && minimum.isNumber() && value.asDouble() < minimum.asDouble()) {
            throw new IllegalArgumentException(field(path) + " must be >= " + minimum.asText());
        }
        JsonNode maximum = schema.get("maximum");
        if (maximum != null && maximum.isNumber() && value.asDouble() > maximum.asDouble()) {
            throw new IllegalArgumentException(field(path) + " must be <= " + maximum.asText());
        }
    }

    private static void validateString(JsonNode value, JsonNode schema, String path) {
        JsonNode minLength = schema.get("minLength");
        if (minLength != null && minLength.isIntegralNumber() && value.asText().length() < minLength.asInt()) {
            throw new IllegalArgumentException(field(path) + " length must be >= " + minLength.asInt());
        }
        JsonNode maxLength = schema.get("maxLength");
        if (maxLength != null && maxLength.isIntegralNumber() && value.asText().length() > maxLength.asInt()) {
            throw new IllegalArgumentException(field(path) + " length must be <= " + maxLength.asInt());
        }
    }

    private static String primaryType(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return null;
        }
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray() && !type.isEmpty() && type.get(0).isTextual()) {
            return type.get(0).asText();
        }
        return null;
    }

    private static String childPath(String parent, String child) {
        return parent == null || parent.isBlank() ? child : parent + "." + child;
    }

    private static String field(String path) {
        return path == null || path.isBlank() ? "root" : path;
    }

    private static String typeName(String type) {
        return switch (type) {
            case "object" -> "object";
            case "array" -> "array";
            case "string" -> "string";
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "null" -> "null";
            default -> type;
        };
    }

    private static String article(String type) {
        return switch (type) {
            case "object", "array", "integer" -> "an ";
            default -> "a ";
        };
    }

    @FunctionalInterface
    public interface ToolInputPreparer {
        Object prepareInput(Object input);
    }

    private record SchemaValidationContext(String fieldLabel) {
    }
}
