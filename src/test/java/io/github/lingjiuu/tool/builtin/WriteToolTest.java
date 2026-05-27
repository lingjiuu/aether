package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteToolTest extends TestCase {

    public void testWriteIncludesLineCountInDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-write-tool-test");
        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = tool.execute(ToolInvocation.builder()
                .toolCall(toolCall("write", "{\"file_path\":\"hello.txt\",\"content\":\"a\\nb\\n\"}"))
                .arguments(Map.of("file_path", "hello.txt", "content", "a\nb\n"))
                .build());

        assertFalse(result.isError());
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
            tool.validateArguments("{\"path\":\"hello.txt\",\"content\":\"hi\"}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Missing required tool argument: file_path")
                || error.getMessage().contains("Unknown tool argument: path"));
    }

    public void testWriteExistingFileRequiresPriorFullRead() throws Exception {
        Path root = Files.createTempDirectory("aether-write-read-required-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);

        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = tool.execute(writeInvocation(new ReadFileState(), "hello.txt", "new"));

        assertTrue(result.isError());
        assertToolTextContains(result, "File has not been read yet");
        assertEquals("old", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testWriteRejectsPartialRead() throws Exception {
        Path root = Files.createTempDirectory("aether-write-partial-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "old\ncontent", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();
        ReadTool readTool = new ReadTool(accessPolicy);
        readTool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        WriteTool tool = new WriteTool(accessPolicy);
        ToolExecutionResult result = tool.execute(writeInvocation(readFileState, "hello.txt", "new"));

        assertTrue(result.isError());
        assertToolTextContains(result, "only partially read");
        assertEquals("old\ncontent", Files.readString(file, StandardCharsets.UTF_8));
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
        ToolExecutionResult result = tool.execute(writeInvocation(readFileState, "hello.txt", "new"));

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
        ToolExecutionResult result = tool.execute(writeInvocation(readFileState, "hello.txt", "new\ncontent\n"));

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
        readTool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"" + path + "\"}"))
                .arguments(Map.of("file_path", path))
                .readFileState(readFileState)
                .build());
    }

    private ToolInvocation writeInvocation(ReadFileState readFileState, String path, String content) {
        return ToolInvocation.builder()
                .toolCall(toolCall("write", "{}"))
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
}
