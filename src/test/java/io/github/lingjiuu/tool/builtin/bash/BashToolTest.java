package io.github.lingjiuu.tool.builtin.bash;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.builtin.shell.ShellOutputCapture;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class BashToolTest extends TestCase {

    public void testBashSeparatesStdoutAndStderrInDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-tool-test");
        BashTool tool = new BashTool(root);

        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, ToolInvocation.builder()
                .toolCall(toolCall("Bash", "{\"command\":\"printf out; printf err >&2; exit 7\"}"))
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

        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, ToolInvocation.builder()
                .toolCall(toolCall("Bash", "{\"command\":\"true\"}"))
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

    public void testBashUsesClaudeStyleToolNameAndTimeoutSchema() throws Exception {
        BashTool tool = new BashTool(Files.createTempDirectory("aether-bash-tool-test"));

        assertEquals("Bash", tool.name());
        assertEquals("Bash", tool.label());
        assertTrue(String.valueOf(tool.inputSchema()).contains("maximum=600000"));

        try {
            tool.validateInputJson("{\"command\":\"true\",\"timeout\":600001}");
            fail("Expected timeout above max to fail validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("timeout"));
        }
    }

    public void testShellOutputCaptureKeepsTailAndPersistsFullOutput() throws Exception {
        ShellOutputCapture output = new ShellOutputCapture("aether-test", 2, 100);
        String fullOutput = "line-1\nline-2\nline-3\nline-4";

        output.appendStdout(fullOutput.getBytes(StandardCharsets.UTF_8), fullOutput.getBytes(StandardCharsets.UTF_8).length);

        ShellOutputCapture.Snapshot snapshot = output.snapshot(true);
        assertTrue(snapshot.stdout().truncated());
        assertEquals("line-3\nline-4", snapshot.stdout().content());
        assertNotNull(snapshot.stdout().fullOutputPath());
        assertEquals(fullOutput, Files.readString(snapshot.stdout().fullOutputPath(), StandardCharsets.UTF_8));
    }

    private ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
    }
}
