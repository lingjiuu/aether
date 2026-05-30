package io.github.lingjiuu.tool.builtin.powershell;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
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

    public void testPowerShellExecutesWhenAvailable() throws Exception {
        if (!PowerShell.isAvailable()) {
            return;
        }
        Path root = Files.createTempDirectory("aether-powershell-tool-test");
        PowerShellTool tool = new PowerShellTool(root);

        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, ToolInvocation.builder()
                .toolCall(toolCall("powershell", "{\"command\":\"Write-Output hello\"}"))
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
