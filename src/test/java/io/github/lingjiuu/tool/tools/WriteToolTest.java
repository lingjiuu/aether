package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteToolTest extends TestCase {

    public void testWriteIncludesLineCountInDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-write-tool-test");
        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = tool.execute(ToolExecutionContext.builder()
                .toolCallId("call-1")
                .toolName("write")
                .argumentsJson("{\"path\":\"hello.txt\",\"content\":\"a\\nb\\n\"}")
                .arguments(Map.of("path", "hello.txt", "content", "a\nb\n"))
                .build());

        assertFalse(result.isError());
        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("write", details.get("kind"));
        assertEquals(2, ((Number) details.get("lineCount")).intValue());
        assertEquals("a", details.get("firstLine"));
    }
}
