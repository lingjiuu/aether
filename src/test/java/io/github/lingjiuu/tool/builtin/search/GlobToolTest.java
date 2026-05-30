package io.github.lingjiuu.tool.builtin.search;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class GlobToolTest extends TestCase {

    public void testGlobReturnsMatchingFiles() throws Exception {
        if (Ripgrep.command().isEmpty()) {
            return;
        }
        Path root = Files.createTempDirectory("aether-glob-tool-test");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/App.kt"), "class App", StandardCharsets.UTF_8);

        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, invocation("Glob", Map.of("pattern", "**/*.java")));

        assertFalse(result.isError());
        assertToolTextContains(result, "src/App.java");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("glob", details.get("kind"));
        assertEquals(1, ((Number) details.get("numFiles")).intValue());
        assertTrue(details.get("durationMs") instanceof Number);
    }

    public void testGlobUsesClaudeStyleToolName() throws Exception {
        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(Files.createTempDirectory("aether-glob-tool-test")));

        assertEquals("Glob", tool.name());
        assertEquals("Glob", tool.label());
    }

    public void testGlobSupportsAbsolutePattern() throws Exception {
        if (Ripgrep.command().isEmpty()) {
            return;
        }
        Path root = Files.createTempDirectory("aether-glob-absolute-pattern-test");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Absolute.java"), "class Absolute {}", StandardCharsets.UTF_8);

        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, invocation("Glob", Map.of(
                "pattern", root.resolve("src").resolve("*.java").toString()
        )));

        assertFalse(result.isError());
        assertToolTextContains(result, "src/Absolute.java");
    }

    public void testGlobSchemaRejectsOldFindLimitArgument() throws Exception {
        Path root = Files.createTempDirectory("aether-glob-schema-test");
        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(root));

        IllegalArgumentException error = null;
        try {
            tool.validateInputJson("{\"pattern\":\"*.md\",\"limit\":10}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Unknown tool argument: limit"));
    }

    public void testGlobUsesClaudeStyleSchemas() throws Exception {
        Path root = Files.createTempDirectory("aether-glob-schema-test");
        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(root));

        @SuppressWarnings("unchecked")
        Map<String, Object> inputProperties = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> patternSchema = (Map<String, Object>) inputProperties.get("pattern");
        assertEquals("The glob pattern to match files against", patternSchema.get("description"));

        Map<String, Object> outputSchema = tool.outputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> outputProperties = (Map<String, Object>) outputSchema.get("properties");
        assertTrue(outputProperties.containsKey("durationMs"));
        assertTrue(outputProperties.containsKey("numFiles"));
        assertTrue(outputProperties.containsKey("filenames"));
        assertTrue(outputProperties.containsKey("truncated"));
        assertEquals(List.of("durationMs", "numFiles", "filenames", "truncated"), outputSchema.get("required"));
        assertFalse(outputSchema.containsKey("additionalProperties"));
    }

    public void testGlobAllowsBlankPatternLikeClaudeSchema() throws Exception {
        Path root = Files.createTempDirectory("aether-glob-schema-test");
        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(root));

        assertEquals("", tool.validateInputJson("{\"pattern\":\"\"}").get("pattern"));
    }

    public void testGlobUsesClaudeStyleTruncationMessage() throws Exception {
        if (Ripgrep.command().isEmpty()) {
            return;
        }
        Path root = Files.createTempDirectory("aether-glob-truncation-test");
        for (int i = 0; i < 101; i++) {
            Files.writeString(root.resolve("file-" + i + ".txt"), "x", StandardCharsets.UTF_8);
        }

        GlobTool tool = new GlobTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, invocation("Glob", Map.of("pattern", "*.txt")));

        assertFalse(result.isError());
        assertToolTextContains(result, "(Results are truncated. Consider using a more specific path or pattern.)");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals(100, ((Number) details.get("numFiles")).intValue());
        assertEquals(Boolean.TRUE, details.get("truncated"));
    }

    private ToolInvocation invocation(String toolName, Map<String, Object> arguments) {
        return ToolInvocation.builder()
                .toolCall(toolCall(toolName))
                .arguments(arguments)
                .build();
    }

    private ToolCallContent toolCall(String toolName) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson("{}")
                .build();
    }

    private void assertToolTextContains(ToolExecutionResult result, String expected) {
        assertFalse(result.getContents().isEmpty());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        String text = ((TextContent) result.getContents().getFirst()).getText();
        assertTrue("Expected tool text to contain " + expected + " but was: " + text, text.contains(expected));
    }
}
