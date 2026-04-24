package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import junit.framework.TestCase;

import java.util.Map;

public class ToolResultMapperTest extends TestCase {

    public void testToMessagePreservesToolCallAndResultFields() {
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("read")
                .argumentsJson("{\"path\":\"a.txt\"}")
                .build();
        ToolExecutionResult result = ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text("file text").getContents())
                .details(Map.of("path", "a.txt"))
                .error(true)
                .build();

        ToolResultMessage message = new ToolResultMapper().toMessage(toolCall, result);

        assertEquals("call-1", message.getToolCallId());
        assertEquals("read", message.getToolName());
        assertTrue(message.isError());
        assertEquals(Map.of("path", "a.txt"), message.getDetails());
        assertEquals("file text", MessageContents.text(message));
    }
}
