package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

public class EditToolTest extends TestCase {

    public void testEditRequiresPriorRead() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-read-required-test");
        Files.writeString(root.resolve("hello.txt"), "hello world", StandardCharsets.UTF_8);

        EditTool tool = new EditTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = tool.execute(editInvocation(
                new ReadFileState(),
                "hello.txt",
                "hello",
                "hi",
                false
        ));

        assertTrue(result.isError());
        assertToolTextContains(result, "File has not been read yet");
        assertEquals("hello world", Files.readString(root.resolve("hello.txt"), StandardCharsets.UTF_8));
    }

    public void testEditSchemaRejectsOldBatchShape() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-schema-test");
        EditTool tool = new EditTool(WorkspaceAccessPolicy.rootedAt(root));

        IllegalArgumentException error = null;
        try {
            tool.validateArguments("{\"path\":\"hello.txt\",\"edits\":[]}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Missing required tool argument: file_path")
                || error.getMessage().contains("Unknown tool argument: path"));
    }

    public void testEditAfterReadUpdatesFileAndReadState() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-after-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();

        read(accessPolicy, readFileState, "hello.txt");
        EditTool tool = new EditTool(accessPolicy);
        ToolExecutionResult result = tool.execute(editInvocation(
                readFileState,
                "hello.txt",
                "hello",
                "hi",
                false
        ));

        assertFalse(result.isError());
        assertEquals("hi world", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("hi world", readFileState.get(file).content());
    }

    public void testEditAfterPartialReadUpdatesFileWhenUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-partial-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();
        ReadTool readTool = new ReadTool(accessPolicy);
        readTool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        EditTool tool = new EditTool(accessPolicy);
        ToolExecutionResult result = tool.execute(editInvocation(
                readFileState,
                "hello.txt",
                "hello",
                "hi",
                false
        ));

        assertFalse(result.isError());
        assertEquals("hi\nworld", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("hi\nworld", readFileState.get(file).content());
        assertFalse(readFileState.get(file).partial());
    }

    public void testEditRejectsFileChangedSincePartialRead() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-partial-stale-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();
        ReadTool readTool = new ReadTool(accessPolicy);
        readTool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        Files.writeString(file, "hello\nuser changed", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(readFileState.get(file).modifiedAt().toMillis() + 5000));

        EditTool tool = new EditTool(accessPolicy);
        ToolExecutionResult result = tool.execute(editInvocation(
                readFileState,
                "hello.txt",
                "hello",
                "hi",
                false
        ));

        assertTrue(result.isError());
        assertToolTextContains(result, "modified since read");
        assertEquals("hello\nuser changed", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testEditRejectsFileChangedSinceRead() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-stale-read-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();

        read(accessPolicy, readFileState, "hello.txt");
        Files.writeString(file, "hello user", StandardCharsets.UTF_8);

        EditTool tool = new EditTool(accessPolicy);
        ToolExecutionResult result = tool.execute(editInvocation(
                readFileState,
                "hello.txt",
                "hello",
                "hi",
                false
        ));

        assertTrue(result.isError());
        assertToolTextContains(result, "modified since read");
        assertEquals("hello user", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testReplaceAllReplacesEveryOccurrence() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-replace-all-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "foo foo foo", StandardCharsets.UTF_8);
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();

        read(accessPolicy, readFileState, "hello.txt");
        EditTool tool = new EditTool(accessPolicy);
        ToolExecutionResult result = tool.execute(editInvocation(
                readFileState,
                "hello.txt",
                "foo",
                "bar",
                true
        ));

        assertFalse(result.isError());
        assertEquals("bar bar bar", Files.readString(file, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals(3, ((Number) details.get("editCount")).intValue());
        assertEquals(Boolean.TRUE, details.get("replaceAll"));
    }

    public void testEditCanCreateMissingFileWithEmptyOldString() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-create-test");
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        ReadFileState readFileState = new ReadFileState();

        EditTool tool = new EditTool(accessPolicy);
        ToolExecutionResult result = tool.execute(editInvocation(
                readFileState,
                "created.txt",
                "",
                "hello\n",
                false
        ));

        assertFalse(result.isError());
        assertEquals("hello\n", Files.readString(root.resolve("created.txt"), StandardCharsets.UTF_8));
        assertEquals("hello\n", readFileState.get(root.resolve("created.txt")).content());
    }

    private void read(WorkspaceAccessPolicy accessPolicy, ReadFileState readFileState, String path) {
        ReadTool readTool = new ReadTool(accessPolicy);
        readTool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"" + path + "\"}"))
                .arguments(Map.of("file_path", path))
                .readFileState(readFileState)
                .build());
    }

    private ToolInvocation editInvocation(
            ReadFileState readFileState,
            String path,
            String oldString,
            String newString,
            boolean replaceAll
    ) {
        return ToolInvocation.builder()
                .toolCall(toolCall("edit", "{}"))
                .arguments(Map.of(
                        "file_path", path,
                        "old_string", oldString,
                        "new_string", newString,
                        "replace_all", replaceAll
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
