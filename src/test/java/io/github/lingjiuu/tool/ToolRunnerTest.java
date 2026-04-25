package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.hook.ToolHook;
import io.github.lingjiuu.tool.hook.ToolHookChain;
import io.github.lingjiuu.tool.permission.PermissionDecision;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ToolRunnerTest extends TestCase {

    public void testUnknownToolReturnsErrorResult() {
        ToolRunner runner = new ToolRunner(new ToolRegistry());

        ToolResultMessage result = runner.run(assistantMessage(), toolCall("missing", "{}"), null);

        assertTrue(result.isError());
        assertEquals("missing", result.getToolName());
        assertTrue(MessageContents.text(result).contains("Unsupported tool: missing"));
    }

    public void testInvalidArgumentsReturnErrorResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequiredTextTool());
        ToolRunner runner = new ToolRunner(registry);

        ToolResultMessage result = runner.run(assistantMessage(), toolCall("required_text", "{}"), null);

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Missing required tool argument: text"));
    }

    public void testPermissionDenialReturnsErrorResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequiredTextTool());
        ToolRunner runner = new ToolRunner(
                registry,
                (invocation, context) -> PermissionDecision.deny("writes are disabled"),
                ToolHookChain.empty()
        );

        ToolResultMessage result = runner.run(
                assistantMessage(),
                toolCall("required_text", "{\"text\":\"ping\"}"),
                null
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Tool permission denied: writes are disabled"));
    }

    public void testBeforeHookCanBlockToolCall() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequiredTextTool());
        ToolRunner runner = new ToolRunner(
                registry,
                (invocation, context) -> PermissionDecision.allow(),
                new ToolHookChain(List.of(new ToolHook() {
                    @Override
                    public PermissionDecision beforeToolCall(ToolInvocation invocation, ToolExecutionContext context) {
                        return PermissionDecision.ask("needs human approval");
                    }
                }))
        );

        ToolResultMessage result = runner.run(
                assistantMessage(),
                toolCall("required_text", "{\"text\":\"ping\"}"),
                null
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Tool permission requires approval: needs human approval"));
    }

    public void testAfterHookCanOverrideResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequiredTextTool());
        ToolRunner runner = new ToolRunner(
                registry,
                (invocation, context) -> PermissionDecision.allow(),
                new ToolHookChain(List.of(new ToolHook() {
                    @Override
                    public ToolExecutionResult afterToolCall(
                            ToolInvocation invocation,
                            ToolExecutionContext context,
                            ToolExecutionResult result
                    ) {
                        return ToolExecutionResult.text("hooked");
                    }
                }))
        );

        ToolResultMessage result = runner.run(
                assistantMessage(),
                toolCall("required_text", "{\"text\":\"ping\"}"),
                null
        );

        assertFalse(result.isError());
        assertEquals("hooked", MessageContents.text(result));
    }

    public void testResultPolicyTruncatesTextAndMarksDetails() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new LongOutputTool());
        ToolRunner runner = new ToolRunner(registry);

        ToolResultMessage result = runner.run(assistantMessage(), toolCall("long_output", "{}"), null);

        assertFalse(result.isError());
        assertTrue(MessageContents.text(result).startsWith("01234"));
        assertTrue(MessageContents.text(result).contains("truncated"));
        assertTrue(result.getDetails() instanceof Map<?, ?>);
        Map<?, ?> details = (Map<?, ?>) result.getDetails();
        assertEquals(true, details.get("truncated"));
        assertEquals(10, details.get("originalTextChars"));
        assertEquals(5, details.get("maxTextChars"));
    }

    private static AssistantMessage assistantMessage() {
        return AssistantMessage.builder()
                .provider("fake")
                .model("test-model")
                .contents(List.of(TextContent.builder().text("I will use a tool.").build()))
                .build();
    }

    private static ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
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
            return "Requires text.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("text", Map.of("type", "string")),
                    "required", List.of("text")
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(String.valueOf(context.getArguments().get("text")));
        }
    }

    private static final class LongOutputTool implements ToolDefinition {
        @Override
        public String name() {
            return "long_output";
        }

        @Override
        public String label() {
            return "long_output";
        }

        @Override
        public String description() {
            return "Returns long output.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public ToolResultPolicy resultPolicy() {
            return ToolResultPolicy.maxTextChars(5);
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text("0123456789").getContents())
                    .details(Map.of("kind", "long"))
                    .error(false)
                    .build();
        }
    }
}
