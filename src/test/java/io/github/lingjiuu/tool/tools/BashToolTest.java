package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class BashToolTest extends TestCase {

    public void testBashSeparatesStdoutAndStderrInDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-tool-test");
        BashTool tool = new BashTool(root);

        ToolExecutionResult result = tool.execute(ToolExecutionContext.builder()
                .toolCallId("call-1")
                .toolName("bash")
                .argumentsJson("{\"command\":\"printf out; printf err >&2; exit 7\"}")
                .arguments(Map.of("command", "printf out; printf err >&2; exit 7"))
                .build());

        assertTrue(result.isError());
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        String text = ((TextContent) result.getContents().getFirst()).getText();
        assertTrue(text.contains("out"));
        assertTrue(text.contains("err"));
        assertTrue(text.contains("Command exited with code 7"));

        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("bash", details.get("kind"));
        assertEquals(7, ((Number) details.get("exitCode")).intValue());
        assertEquals("out", details.get("stdout"));
        assertEquals("err", details.get("stderr"));
        assertTrue(String.valueOf(details.get("aggregatedOutput")).contains("out"));
        assertTrue(String.valueOf(details.get("aggregatedOutput")).contains("err"));
        assertEquals(Boolean.FALSE, details.get("stdoutTruncated"));
        assertEquals(Boolean.FALSE, details.get("stderrTruncated"));
        assertFalse(details.containsKey("outputPreview"));
        assertFalse(details.containsKey("lineCount"));
        assertFalse(details.containsKey("byteCount"));
        assertFalse(details.containsKey("totalLines"));
        assertFalse(details.containsKey("totalBytes"));
        assertFalse(details.containsKey("fullOutputPath"));
    }

    public void testBashNoOutputKeepsStructuredEmptyStreams() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-tool-test");
        BashTool tool = new BashTool(root);

        ToolExecutionResult result = tool.execute(ToolExecutionContext.builder()
                .toolCallId("call-1")
                .toolName("bash")
                .argumentsJson("{\"command\":\"true\"}")
                .arguments(Map.of("command", "true"))
                .build());

        assertFalse(result.isError());
        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("", details.get("stdout"));
        assertEquals("", details.get("stderr"));
        assertEquals("", details.get("aggregatedOutput"));
        assertFalse(details.containsKey("outputPreview"));
    }
}
