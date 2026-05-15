package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import junit.framework.TestCase;

import java.util.Map;

public class ToolResultMessageMapperTest extends TestCase {

    public void testToMessagePreservesToolCallAndResultFields() {
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("sample_tool")
                .argumentsJson("{\"value\":\"a\"}")
                .build();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text("file text").getContents())
                .details(Map.of("path", "a.txt"))
                .error(true)
                .build();

        ToolResultMessage message = new ToolResultMessageMapper().toMessage(toolCall, result);

        assertEquals("call-1", message.getToolCallId());
        assertEquals("sample_tool", message.getToolName());
        assertTrue(message.isError());
        assertEquals(Map.of("path", "a.txt"), message.getDetails());
        assertEquals("file text", MessageContents.text(message));
    }
}
