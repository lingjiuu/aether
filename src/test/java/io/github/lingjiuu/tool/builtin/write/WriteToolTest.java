package io.github.lingjiuu.tool.builtin.write;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.builtin.read.ReadTool;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

public class WriteToolTest extends TestCase {

    public void testWriteIncludesLineCountInDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-write-tool-test");
        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));

        assertEquals("Write", tool.name());
        assertEquals("Write", tool.label());
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, ToolInvocation.builder()
                .toolCall(toolCall("Write", "{\"file_path\":\"hello.txt\",\"content\":\"a\\nb\\n\"}"))
                .arguments(Map.of("file_path", "hello.txt", "content", "a\nb\n"))
                .build());

        assertFalse(toolText(result), result.isError());
        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("write", details.get("kind"));
        assertEquals("create", details.get("operation"));
        assertEquals(2, ((Number) details.get("lineCount")).intValue());
        assertEquals("a", details.get("firstLine"));
    }

    public void testWriteSchemaRejectsOldPathArgument() throws Exception {
        Path root = Files.createTempDirectory("aether-write-schema-test");
        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));

        IllegalArgumentException error = null;
        try {
            tool.validateInputJson("{\"path\":\"hello.txt\",\"content\":\"hi\"}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Missing required tool argument: file_path")
                || error.getMessage().contains("Unknown tool argument: path"));
    }

    public void testWriteOutputSchemaUsesClaudeCodeShape() throws Exception {
        Path root = Files.createTempDirectory("aether-write-output-schema-test");
        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));

        @SuppressWarnings("unchecked")
        Map<String, Object> outputSchema = tool.outputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) outputSchema.get("properties");

        assertTrue(properties.containsKey("type"));
        assertTrue(properties.containsKey("filePath"));
        assertTrue(properties.containsKey("content"));
        assertTrue(properties.containsKey("structuredPatch"));
        assertTrue(properties.containsKey("originalFile"));
        assertTrue(properties.containsKey("gitDiff"));
        assertFalse(outputSchema.containsKey("additionalProperties"));

        @SuppressWarnings("unchecked")
        Map<String, Object> gitDiff = (Map<String, Object>) properties.get("gitDiff");
        assertEquals("object", gitDiff.get("type"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) outputSchema.get("required");
        assertFalse(required.contains("gitDiff"));
    }

    public void testWriteExistingFileRequiresPriorRead() throws Exception {
        Path root = Files.createTempDirectory("aether-write-read-required-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);

        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, writeInvocation(new ReadFileState(), "hello.txt", "new"));

        assertTrue(result.isError());
        assertToolTextEquals(result, "File has not been read yet. Read it first before writing to it.");
        assertEquals("old", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testWriteRejectsPartialRead() throws Exception {
        Path root = Files.createTempDirectory("aether-write-partial-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old\ncontent", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();
        ReadTool readTool = new ReadTool(accessPolicy);
        io.github.lingjiuu.tool.ToolTestSupport.execute(readTool, ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        WriteTool tool = new WriteTool(accessPolicy);
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, writeInvocation(readFileState, "hello.txt", "new"));

        assertTrue(result.isError());
        assertToolTextEquals(result, "File has not been read yet. Read it first before writing to it.");
        assertEquals("old\ncontent", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(readFileState.get(file).partial());
    }

    public void testWriteRejectsFileChangedSincePartialRead() throws Exception {
        Path root = Files.createTempDirectory("aether-write-partial-stale-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old\ncontent", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();
        ReadTool readTool = new ReadTool(accessPolicy);
        io.github.lingjiuu.tool.ToolTestSupport.execute(readTool, ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        Files.writeString(file, "old\nuser changed", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(readFileState.get(file).modifiedAt().toMillis() + 5000));

        WriteTool tool = new WriteTool(accessPolicy);
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, writeInvocation(readFileState, "hello.txt", "new"));

        assertTrue(result.isError());
        assertToolTextEquals(result, "File has not been read yet. Read it first before writing to it.");
        assertEquals("old\nuser changed", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testWriteRejectsFileChangedSinceRead() throws Exception {
        Path root = Files.createTempDirectory("aether-write-stale-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();

        read(accessPolicy, readFileState, "hello.txt");
        Files.writeString(file, "user changed", StandardCharsets.UTF_8);

        WriteTool tool = new WriteTool(accessPolicy);
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, writeInvocation(readFileState, "hello.txt", "new"));

        assertTrue(result.isError());
        assertToolTextContains(result, "modified since read");
        assertEquals("user changed", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testWriteExistingFileAfterReadUpdatesFileAndReadState() throws Exception {
        Path root = Files.createTempDirectory("aether-write-after-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();

        read(accessPolicy, readFileState, "hello.txt");
        WriteTool tool = new WriteTool(accessPolicy);
        ToolExecutionResult result = io.github.lingjiuu.tool.ToolTestSupport.execute(tool, writeInvocation(readFileState, "hello.txt", "new\ncontent\n"));

        assertFalse(result.isError());
        assertEquals("new\ncontent\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("new\ncontent\n", readFileState.get(file).content());
        assertFalse(readFileState.get(file).partial());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("update", details.get("operation"));
        assertEquals(2, ((Number) details.get("lineCount")).intValue());
    }

    private void read(WorkspaceAccessPolicy accessPolicy, ReadFileState readFileState, String path) {
        ReadTool readTool = new ReadTool(accessPolicy);
        io.github.lingjiuu.tool.ToolTestSupport.execute(readTool, ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"" + path + "\"}"))
                .arguments(Map.of("file_path", path))
                .readFileState(readFileState)
                .build());
    }

    private ToolInvocation writeInvocation(ReadFileState readFileState, String path, String content) {
        return ToolInvocation.builder()
                .toolCall(toolCall("Write", "{}"))
                .arguments(Map.of(
                        "file_path", path,
                        "content", content
                ))
                .readFileState(readFileState)
                .build();
    }

    private ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
    }

    private void assertToolTextContains(ToolExecutionResult result, String expected) {
        assertFalse(result.getContents().isEmpty());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        String text = ((TextContent) result.getContents().getFirst()).getText();
        assertTrue("Expected tool text to contain " + expected + " but was: " + text, text.contains(expected));
    }

    private void assertToolTextEquals(ToolExecutionResult result, String expected) {
        assertFalse(result.getContents().isEmpty());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        String text = ((TextContent) result.getContents().getFirst()).getText();
        assertEquals(expected, text);
    }

    private String toolText(ToolExecutionResult result) {
        if (result.getContents().isEmpty() || !(result.getContents().getFirst() instanceof TextContent textContent)) {
            return "";
        }
        return textContent.getText();
    }
}
