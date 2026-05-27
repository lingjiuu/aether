package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class GrepToolTest extends TestCase {

    public void testGrepDefaultsToFilesWithMatches() throws Exception {
        if (ExecutableFinder.findOnPath("rg").isEmpty()) {
            return;
        }
        Path root = fixtureRoot();
        GrepTool tool = new GrepTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = tool.execute(invocation(Map.of("pattern", "hello")));

        assertFalse(result.isError());
        assertToolTextContains(result, "Found 2 files");
        assertToolTextContains(result, "a.txt");
        assertToolTextContains(result, "nested/b.txt");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("grep", details.get("kind"));
        assertEquals("files_with_matches", details.get("mode"));
        assertEquals(2, ((Number) details.get("numFiles")).intValue());
    }

    public void testGrepContentModeReturnsMatchingLines() throws Exception {
        if (ExecutableFinder.findOnPath("rg").isEmpty()) {
            return;
        }
        Path root = fixtureRoot();
        GrepTool tool = new GrepTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = tool.execute(invocation(Map.of(
                "pattern", "hello",
                "output_mode", "content",
                "-n", true
        )));

        assertFalse(result.isError());
        assertToolTextContains(result, "a.txt:1:hello");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("content", details.get("mode"));
        assertEquals(2, ((Number) details.get("numLines")).intValue());
    }

    public void testGrepCountModeSummarizesOccurrences() throws Exception {
        if (ExecutableFinder.findOnPath("rg").isEmpty()) {
            return;
        }
        Path root = fixtureRoot();
        GrepTool tool = new GrepTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = tool.execute(invocation(Map.of(
                "pattern", "hello",
                "output_mode", "count",
                "-i", true
        )));

        assertFalse(result.isError());
        assertToolTextContains(result, "Found 3 occurrences across 2 files.");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("count", details.get("mode"));
        assertEquals(3, ((Number) details.get("numMatches")).intValue());
        assertEquals(2, ((Number) details.get("numFiles")).intValue());
    }

    public void testGrepSchemaRejectsOldArguments() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-schema-test");
        GrepTool tool = new GrepTool(WorkspaceAccessPolicy.rootedAt(root));

        IllegalArgumentException error = null;
        try {
            tool.validateArguments("{\"pattern\":\"hello\",\"ignoreCase\":true}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Unknown tool argument: ignoreCase"));
    }

    private Path fixtureRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-tool-test");
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("a.txt"), "hello\nworld\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("nested/b.txt"), "hello again\nHELLO\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("c.txt"), "nothing\n", StandardCharsets.UTF_8);
        return root;
    }

    private ToolInvocation invocation(Map<String, Object> arguments) {
        return ToolInvocation.builder()
                .toolCall(toolCall("grep"))
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
