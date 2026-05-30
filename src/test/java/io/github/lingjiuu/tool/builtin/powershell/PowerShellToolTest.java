package io.github.lingjiuu.tool.builtin.powershell;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class PowerShellToolTest extends TestCase {

    public void testPowerShellSchemaRejectsUnknownArguments() throws Exception {
        Path root = Files.createTempDirectory("aether-powershell-schema-test");
        PowerShellTool tool = new PowerShellTool(root);

        IllegalArgumentException error = null;
        try {
            tool.validateInputJson("{\"command\":\"Get-ChildItem\",\"shell\":\"cmd\"}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Unknown tool argument: shell"));
    }

    public void testPowerShellUsesClaudeStyleToolNameAndTimeoutSchema() throws Exception {
        PowerShellTool tool = new PowerShellTool(Files.createTempDirectory("aether-powershell-schema-test"));

        assertEquals("PowerShell", tool.name());
        assertEquals("PowerShell", tool.label());

        @SuppressWarnings("unchecked")
        Map<String, Object> inputProperties = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> commandSchema = (Map<String, Object>) inputProperties.get("command");
        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutSchema = (Map<String, Object>) inputProperties.get("timeout");
        assertEquals("The PowerShell command to execute", commandSchema.get("description"));
        assertEquals("Optional timeout in milliseconds (max 600000)", timeoutSchema.get("description"));
        assertEquals(600000, ((Number) timeoutSchema.get("maximum")).intValue());

        Map<String, Object> parsed = tool.validateInputJson("{\"command\":\"Get-ChildItem\",\"timeout\":\"1000\"}");
        assertEquals(1000, ((Number) parsed.get("timeout")).intValue());

        try {
            tool.validateInputJson("{\"command\":\"Get-ChildItem\",\"timeout\":600001}");
            fail("Expected timeout above max to fail validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("timeout"));
        }
    }

    public void testPowerShellUsesClaudeStyleOutputSchema() throws Exception {
        PowerShellTool tool = new PowerShellTool(Files.createTempDirectory("aether-powershell-schema-test"));

        Map<String, Object> outputSchema = tool.outputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> outputProperties = (Map<String, Object>) outputSchema.get("properties");

        assertTrue(outputProperties.containsKey("stdout"));
        assertTrue(outputProperties.containsKey("stderr"));
        assertTrue(outputProperties.containsKey("interrupted"));
        assertTrue(outputProperties.containsKey("returnCodeInterpretation"));
        assertTrue(outputProperties.containsKey("isImage"));
        assertTrue(outputProperties.containsKey("persistedOutputPath"));
        assertTrue(outputProperties.containsKey("persistedOutputSize"));
        assertFalse(outputProperties.containsKey("backgroundTaskId"));
        assertEquals(List.of("stdout", "stderr", "interrupted"), outputSchema.get("required"));
        assertFalse(outputSchema.containsKey("additionalProperties"));
    }

    public void testPowerShellExecutesWhenAvailable() throws Exception {
        if (!PowerShell.isAvailable()) {
            return;
        }
        Path root = Files.createTempDirectory("aether-powershell-tool-test");
        PowerShellTool tool = new PowerShellTool(root);

        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, ToolInvocation.builder()
                .toolCall(toolCall("PowerShell", "{\"command\":\"Write-Output hello\"}"))
                .arguments(Map.of("command", "Write-Output hello"))
                .build());

        assertFalse(result.isError());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        String text = ((TextContent) result.getContents().getFirst()).getText();
        assertTrue(text.contains("hello"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("powershell", details.get("kind"));
        assertEquals(0, ((Number) details.get("exitCode")).intValue());
    }

    private ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
    }
}
