package io.github.lingjiuu.tool;

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
        Map<String, Object> parametersSchema,
        ToolRiskLevel riskLevel
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        label = label == null || label.isBlank() ? name : label;
        description = description == null ? "" : description;
        parametersSchema = parametersSchema == null ? Map.of() : Map.copyOf(parametersSchema);
        riskLevel = riskLevel == null ? ToolRiskLevel.UNKNOWN : riskLevel;
    }

    public static ToolSpec of(
            String name,
            String label,
            String description,
            Map<String, Object> parametersSchema,
            ToolRiskLevel riskLevel
    ) {
        return new ToolSpec(
                name,
                label,
                description,
                parametersSchema,
                riskLevel
        );
    }

    public Map<String, Object> validateArguments(String argumentsJson) {
        return validateArguments(argumentsJson, null);
    }

    public Map<String, Object> validateArguments(String argumentsJson, ToolArgumentPreparer preparer) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            JsonNode argumentsNode = OBJECT_MAPPER.readTree(json);
            Object rawArguments = OBJECT_MAPPER.convertValue(argumentsNode, Object.class);
            Object preparedArguments = preparer == null ? rawArguments : preparer.prepareArguments(rawArguments);
            JsonNode preparedNode = OBJECT_MAPPER.valueToTree(preparedArguments);
            if (preparedNode == null || !preparedNode.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }

            JsonNode parametersNode = parametersSchema.isEmpty()
                    ? null
                    : OBJECT_MAPPER.valueToTree(parametersSchema);
            if (parametersNode != null) {
                validateNode(preparedNode, parametersNode, "");
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

    private static void validateNode(JsonNode value, JsonNode schema, String path) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }

        validateType(value, schema, path);
        validateEnum(value, schema, path);

        String type = primaryType(schema);
        if ("object".equals(type) || value.isObject() && schema.has("properties")) {
            validateObject(value, schema, path);
        }
        if ("array".equals(type) || value.isArray() && schema.has("items")) {
            validateArray(value, schema, path);
        }
        if (value.isNumber()) {
            validateNumber(value, schema, path);
        }
        if (value.isTextual()) {
            validateString(value, schema, path);
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

    private static void validateObject(JsonNode value, JsonNode schema, String path) {
        if (!value.isObject()) {
            return;
        }

        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            List<String> requiredFields = OBJECT_MAPPER.convertValue(requiredNode, new TypeReference<>() {
            });
            for (String field : requiredFields) {
                JsonNode child = value.get(field);
                if (child == null || child.isNull()) {
                    throw new IllegalArgumentException("Missing required tool argument: " + childPath(path, field));
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
                validateNode(child, propertySchema, childPath(path, field));
                continue;
            }
            if (additionalPropertiesNode != null && additionalPropertiesNode.isBoolean()
                    && !additionalPropertiesNode.asBoolean()) {
                throw new IllegalArgumentException("Unknown tool argument: " + childPath(path, field));
            }
            if (additionalPropertiesNode != null && additionalPropertiesNode.isObject()) {
                validateNode(child, additionalPropertiesNode, childPath(path, field));
            }
        }
    }

    private static void validateArray(JsonNode value, JsonNode schema, String path) {
        if (!value.isArray()) {
            return;
        }
        JsonNode itemsNode = schema.get("items");
        if (itemsNode == null || !itemsNode.isObject()) {
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            validateNode(value.get(i), itemsNode, path + "[" + i + "]");
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
    public interface ToolArgumentPreparer {
        Object prepareArguments(Object arguments);
    }
}
