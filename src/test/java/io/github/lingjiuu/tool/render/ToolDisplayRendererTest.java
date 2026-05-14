package io.github.lingjiuu.tool.render;

import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ToolDisplayRendererTest extends TestCase {

    public void testRenderCallFallsBackWhenToolHasNoRenderer() {
        ToolDisplayRenderer renderer = new ToolDisplayRenderer(null);

        ToolRenderedOutput output = renderer.renderCall(ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("custom")
                .argumentsJson("{\"path\":\"README.md\"}")
                .build());

        assertFalse(output.hidden());
        assertEquals("custom {\"path\":\"README.md\"}", output.text());
    }

    public void testRenderResultFallsBackWhenRendererThrows() {
        ToolDisplayRenderer renderer = new ToolDisplayRenderer(name -> new ThrowingRenderTool());

        ToolRenderedOutput output = renderer.renderResult(ToolResultMessage.builder()
                .toolName("throwing")
                .toolCallId("call-1")
                .contents(List.of(TextContent.builder().text("plain result").build()))
                .build());

        assertEquals("plain result", output.text());
    }

    public void testRenderCallUsesToolDefinitionRenderer() {
        ToolDisplayRenderer renderer = new ToolDisplayRenderer(name -> new CustomRenderTool());

        ToolRenderedOutput output = renderer.renderCall(ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("custom")
                .argumentsJson("{\"path\":\"src\"}")
                .build());

        assertEquals("custom render src", output.text());
    }

    private static class CustomRenderTool implements ToolDefinition {
        @Override
        public String name() {
            return "custom";
        }

        @Override
        public String label() {
            return "custom";
        }

        @Override
        public String description() {
            return "Custom renderer.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public ToolRenderedOutput renderCall(ToolRenderRequest request) {
            return ToolRenderedOutput.text("custom render " + request.arguments().get("path"));
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text("ok");
        }
    }

    private static final class ThrowingRenderTool extends CustomRenderTool {
        @Override
        public ToolRenderedOutput renderResult(ToolRenderRequest request) {
            throw new IllegalStateException("boom");
        }
    }
}
