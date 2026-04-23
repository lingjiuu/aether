package io.github.lingjiuu.tool;

import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ToolArgumentValidatorTest extends TestCase {

    public void testValidateReturnsParsedArgumentsForRequiredFields() {
        ToolArgumentValidator validator = new ToolArgumentValidator();

        Map<String, Object> arguments = validator.validate(new RequiredTextTool(), "{\"text\":\"ping\"}");

        assertEquals(Map.of("text", "ping"), arguments);
    }

    public void testValidateRejectsMissingRequiredFields() {
        ToolArgumentValidator validator = new ToolArgumentValidator();

        try {
            validator.validate(new RequiredTextTool(), "{}");
            fail("Expected missing required field to throw");
        } catch (IllegalArgumentException error) {
            assertEquals("Missing required tool argument: text", error.getMessage());
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
                            "text", Map.of("type", "string")
                    ),
                    "required", List.of("text")
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(String.valueOf(context.getArguments().get("text")));
        }
    }
}
