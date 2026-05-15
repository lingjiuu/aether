package io.github.lingjiuu.tool.validation;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ToolSchemaValidatorTest extends TestCase {

    public void testValidateReturnsParsedArgumentsForRequiredFields() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        Map<String, Object> arguments = validator.validate(new RequiredTextTool(), "{\"text\":\"ping\"}");

        assertEquals(Map.of("text", "ping"), arguments);
    }

    public void testValidateRejectsMissingRequiredFields() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        try {
            validator.validate(new RequiredTextTool(), "{}");
            fail("Expected missing required field to throw");
        } catch (IllegalArgumentException error) {
            assertEquals("Missing required tool argument: text", error.getMessage());
        }
    }

    public void testValidateRejectsMalformedJson() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        try {
            validator.validate(new RequiredTextTool(), "{");
            fail("Expected malformed JSON to throw");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("Invalid tool arguments JSON"));
        }
    }

    public void testValidateRejectsNonObjectRoot() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        try {
            validator.validate(new RequiredTextTool(), "[]");
            fail("Expected non-object arguments to throw");
        } catch (IllegalArgumentException error) {
            assertEquals("Tool arguments must be a JSON object", error.getMessage());
        }
    }

    public void testValidateRejectsWrongPrimitiveTypes() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        assertValidationMessage(validator, "{\"text\":123}", "text must be a string");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"limit\":\"2\"}", "limit must be an integer");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"ratio\":\"1.5\"}", "ratio must be a number");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"enabled\":\"true\"}", "enabled must be a boolean");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"tags\":\"a\"}", "tags must be an array");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"options\":[]}", "options must be an object");
    }

    public void testValidateRejectsDecimalForInteger() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        assertValidationMessage(validator, "{\"text\":\"ping\",\"limit\":1.5}", "limit must be an integer");
    }

    public void testValidateRejectsUnknownFieldsWhenAdditionalPropertiesFalse() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        assertValidationMessage(validator, "{\"text\":\"ping\",\"extra\":true}", "Unknown tool argument: extra");
    }

    public void testValidateRejectsEnumAndRangeViolations() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        assertValidationMessage(validator, "{\"text\":\"ping\",\"mode\":\"slow\"}", "mode must be one of");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"limit\":0}", "limit must be >= 1");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"ratio\":11}", "ratio must be <= 10");
    }

    public void testValidateRejectsNestedObjectAndArrayViolationsWithPath() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        assertValidationMessage(validator, "{\"text\":\"ping\",\"options\":{\"depth\":\"2\"}}", "options.depth must be an integer");
        assertValidationMessage(validator, "{\"text\":\"ping\",\"tags\":[\"ok\", 2]}", "tags[1] must be a string");
    }

    public void testValidateAppliesPrepareArgumentsBeforeSchemaValidation() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        Map<String, Object> arguments = validator.validate(new PreparedTextTool(), "{\"message\":\"ping\"}");

        assertEquals(Map.of("text", "ping"), arguments);
    }

    public void testValidatePrepareArgumentsRunsBeforeAdditionalPropertiesCheck() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        Map<String, Object> arguments = validator.validate(new PreparedTextTool(), "{\"message\":\"pong\"}");

        assertEquals(Map.of("text", "pong"), arguments);
    }

    public void testValidateRejectsPreparedNonObjectRoot() {
        ToolSchemaValidator validator = new ToolSchemaValidator();

        try {
            validator.validate(new NonObjectPreparingTool(), "{\"text\":\"ping\"}");
            fail("Expected prepared non-object root to throw");
        } catch (IllegalArgumentException error) {
            assertEquals("Tool arguments must be a JSON object", error.getMessage());
        }
    }

    private void assertValidationMessage(ToolSchemaValidator validator, String argumentsJson, String expected) {
        try {
            validator.validate(new RequiredTextTool(), argumentsJson);
            fail("Expected validation error containing: " + expected);
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }

    private static final class RequiredTextTool implements ToolDefinition {
        @Override
        public String name() {
            return "required_text";
        }

        @Override
        public String label() {
            return "required_text";
        }

        @Override
        public String description() {
            return "Requires one text argument.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "text", Map.of("type", "string"),
                            "limit", Map.of("type", "integer", "minimum", 1),
                            "ratio", Map.of("type", "number", "maximum", 10),
                            "enabled", Map.of("type", "boolean"),
                            "mode", Map.of("type", "string", "enum", List.of("fast", "normal")),
                            "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                            "options", Map.of(
                                    "type", "object",
                                    "properties", Map.of("depth", Map.of("type", "integer")),
                                    "additionalProperties", false
                            )
                    ),
                    "required", List.of("text"),
                    "additionalProperties", false
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(String.valueOf(context.getArguments().get("text")));
        }
    }

    private static class PreparedTextTool implements ToolDefinition {
        @Override
        public String name() {
            return "prepared_text";
        }

        @Override
        public String label() {
            return "prepared_text";
        }

        @Override
        public String description() {
            return "Prepares message into text.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("text", Map.of("type", "string")),
                    "required", List.of("text"),
                    "additionalProperties", false
            );
        }

        @Override
        public Object prepareArguments(Object arguments) {
            if (!(arguments instanceof Map<?, ?> map)) {
                return arguments;
            }
            if (!(map.get("message") instanceof String message)) {
                return arguments;
            }
            return Map.of("text", message);
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(String.valueOf(context.getArguments().get("text")));
        }
    }

    private static final class NonObjectPreparingTool extends PreparedTextTool {
        @Override
        public Object prepareArguments(Object arguments) {
            return "not-an-object";
        }
    }
}
