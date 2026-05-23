package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ReadToolTest extends TestCase {

    public void testReadTextFileWithoutLimitOmitsLimitFromDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-read-tool-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = tool.execute(ToolExecutionContext.builder()
                .toolCallId("call-1")
                .toolName("read")
                .argumentsJson("{\"path\":\"hello.txt\"}")
                .arguments(Map.of("path", "hello.txt"))
                .build());

        assertFalse(result.isError());
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        assertEquals("hello world", ((TextContent) result.getContents().getFirst()).getText());

        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("read", details.get("kind"));
        assertEquals("hello.txt", details.get("path"));
        assertEquals(1, ((Number) details.get("offset")).intValue());
        assertFalse(details.containsKey("limit"));
        assertEquals(Boolean.FALSE, details.get("hasMore"));
    }
}
