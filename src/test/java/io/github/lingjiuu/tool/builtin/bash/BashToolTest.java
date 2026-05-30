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
import java.util.List;
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
        assertTrue(text.contains("Exit code 7"));

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
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        assertEquals("", ((TextContent) result.getContents().getFirst()).getText());
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

        @SuppressWarnings("unchecked")
        Map<String, Object> inputProperties = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> commandSchema = (Map<String, Object>) inputProperties.get("command");
        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutSchema = (Map<String, Object>) inputProperties.get("timeout");
        assertEquals("The command to execute", commandSchema.get("description"));
        assertEquals("Optional timeout in milliseconds (max 600000)", timeoutSchema.get("description"));
        assertEquals(600000, ((Number) timeoutSchema.get("maximum")).intValue());

        Map<String, Object> parsed = tool.validateInputJson("{\"command\":\"true\",\"timeout\":\"1000\"}");
        assertEquals(1000, ((Number) parsed.get("timeout")).intValue());

        try {
            tool.validateInputJson("{\"command\":\"true\",\"timeout\":600001}");
            fail("Expected timeout above max to fail validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("timeout"));
        }
    }

    public void testBashUsesClaudeStyleOutputSchema() throws Exception {
        BashTool tool = new BashTool(Files.createTempDirectory("aether-bash-tool-test"));

        Map<String, Object> outputSchema = tool.outputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> outputProperties = (Map<String, Object>) outputSchema.get("properties");

        assertTrue(outputProperties.containsKey("stdout"));
        assertTrue(outputProperties.containsKey("stderr"));
        assertTrue(outputProperties.containsKey("rawOutputPath"));
        assertTrue(outputProperties.containsKey("interrupted"));
        assertTrue(outputProperties.containsKey("isImage"));
        assertTrue(outputProperties.containsKey("returnCodeInterpretation"));
        assertTrue(outputProperties.containsKey("noOutputExpected"));
        assertTrue(outputProperties.containsKey("structuredContent"));
        assertTrue(outputProperties.containsKey("persistedOutputPath"));
        assertTrue(outputProperties.containsKey("persistedOutputSize"));
        assertEquals(List.of("stdout", "stderr", "interrupted"), outputSchema.get("required"));
        assertFalse(outputSchema.containsKey("additionalProperties"));
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
