package io.github.lingjiuu.tool.builtin.read;

import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ReadToolTest extends TestCase {

    public void testReadToolContractMatchesClaudeCodeShape() throws Exception {
        Path root = Files.createTempDirectory("aether-read-contract-test");
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        assertEquals("Read", tool.name());
        assertEquals("Read", tool.label());
        assertTrue(tool.description().contains("Reads a file from the local filesystem. You can access any file directly by using this tool."));
        assertTrue(tool.description().contains("Results are returned using cat -n format, with line numbers starting at 1"));
        assertTrue(tool.description().contains("This tool allows Claude Code to read images"));
        assertFalse(tool.description().contains("PDF"));
        assertFalse(tool.description().contains("Jupyter"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inputProperties = (Map<String, Object>) tool.inputSchema().get("properties");
        assertEquals("The absolute path to the file to read", ((Map<?, ?>) inputProperties.get("file_path")).get("description"));
        assertEquals("number", ((Map<?, ?>) inputProperties.get("offset")).get("type"));
        assertEquals(0, ((Number) ((Map<?, ?>) inputProperties.get("offset")).get("minimum")).intValue());
        assertEquals("number", ((Map<?, ?>) inputProperties.get("limit")).get("type"));
        assertFalse(inputProperties.containsKey("pages"));

        assertTrue(tool.outputSchema().containsKey("oneOf"));
    }

    public void testReadTextFileUsesCompactCatNFormat() throws Exception {
        Path root = Files.createTempDirectory("aether-read-tool-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello world\nsecond line", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = execute(tool, Map.of("file_path", "hello.txt"), readFileState);

        assertFalse(result.isError());
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        String text = text(result);
        assertTrue(text.startsWith("1\thello world\n2\tsecond line"));
        assertTrue(text.contains("<system-reminder>"));

        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("read", details.get("kind"));
        assertEquals("hello.txt", details.get("path"));
        assertEquals(1, ((Number) details.get("offset")).intValue());
        assertEquals(Boolean.FALSE, details.get("hasMore"));
        assertNotNull(readFileState.get(file));
        assertFalse(readFileState.get(file).partial());
        assertEquals("hello world\nsecond line", readFileState.get(file).content());
    }

    public void testReadSchemaRejectsOldPathArgument() throws Exception {
        Path root = Files.createTempDirectory("aether-read-schema-test");
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        IllegalArgumentException error = null;
        try {
            tool.validateInputJson("{\"path\":\"hello.txt\"}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Missing required tool argument: file_path")
                || error.getMessage().contains("Unknown tool argument: path"));
    }

    public void testOffsetAndLimitAcceptSemanticNumberStrings() throws Exception {
        Path root = Files.createTempDirectory("aether-read-semantic-number-test");
        Files.writeString(root.resolve("hello.txt"), "one\ntwo\nthree", StandardCharsets.UTF_8);
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = execute(tool, Map.of(
                "file_path", "hello.txt",
                "offset", "2",
                "limit", "1"
        ), new ReadFileState());

        assertFalse(result.isError());
        assertTrue(text(result).startsWith("2\ttwo"));
    }

    public void testPartialReadRecordsPartialState() throws Exception {
        Path root = Files.createTempDirectory("aether-read-partial-state-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = execute(tool, Map.of("file_path", "hello.txt", "limit", 1), readFileState);

        assertFalse(result.isError());
        assertTrue(text(result).startsWith("1\thello"));
        assertNotNull(readFileState.get(file));
        assertTrue(readFileState.get(file).partial());
    }

    public void testRepeatedUnchangedReadReturnsFileUnchangedStub() throws Exception {
        Path root = Files.createTempDirectory("aether-read-file-unchanged-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult first = execute(tool, Map.of("file_path", "hello.txt"), readFileState);
        ToolExecutionResult second = execute(tool, Map.of("file_path", "hello.txt"), readFileState);

        assertFalse(first.isError());
        assertFalse(second.isError());
        assertEquals("File unchanged since last read. The content from the earlier Read tool_result in this conversation is still current — refer to that instead of re-reading.", text(second));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) second.getDetails();
        assertEquals("file_unchanged", details.get("fileType"));
        assertEquals(file.toRealPath(), readFileState.get(file).path());
    }

    public void testPartialReadDoesNotDowngradeExistingFullReadState() throws Exception {
        Path root = Files.createTempDirectory("aether-read-preserve-full-state-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        execute(tool, Map.of("file_path", "hello.txt"), readFileState);
        execute(tool, Map.of("file_path", "hello.txt", "limit", 1), readFileState);

        assertNotNull(readFileState.get(file));
        assertFalse(readFileState.get(file).partial());
        assertEquals("hello\nworld", readFileState.get(file).content());
    }

    public void testEmptyFileReturnsSystemReminder() throws Exception {
        Path root = Files.createTempDirectory("aether-read-empty-test");
        Files.writeString(root.resolve("empty.txt"), "", StandardCharsets.UTF_8);
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = execute(tool, Map.of("file_path", "empty.txt"), new ReadFileState());

        assertFalse(result.isError());
        assertEquals("<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>", text(result));
    }

    public void testOffsetBeyondEndReturnsSystemReminder() throws Exception {
        Path root = Files.createTempDirectory("aether-read-offset-beyond-test");
        Files.writeString(root.resolve("hello.txt"), "one\ntwo", StandardCharsets.UTF_8);
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = execute(tool, Map.of("file_path", "hello.txt", "offset", 10), new ReadFileState());

        assertFalse(result.isError());
        assertEquals("<system-reminder>Warning: the file exists but is shorter than the provided offset (10). The file has 2 lines.</system-reminder>", text(result));
    }

    public void testImageReadReturnsImageOnlyAndDoesNotRecordTextReadState() throws Exception {
        Path root = Files.createTempDirectory("aether-read-image-test");
        Path file = root.resolve("image.png");
        Files.write(file, minimalPngHeader());
        ReadFileState readFileState = new ReadFileState();

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = execute(tool, Map.of("file_path", "image.png"), readFileState);

        assertFalse(result.isError());
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().getFirst() instanceof ImageContent);
        assertNull(readFileState.get(file));
    }

    public void testBinaryExtensionIsRejected() throws Exception {
        Path root = Files.createTempDirectory("aether-read-binary-ext-test");
        Files.write(root.resolve("archive.zip"), new byte[]{1, 2, 3});
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = execute(tool, Map.of("file_path", "archive.zip"), new ReadFileState());

        assertTrue(result.isError());
        assertTrue(text(result).contains("cannot read binary files"));
    }

    public void testLargeFileRequiresOffsetAndLimit() throws Exception {
        Path root = Files.createTempDirectory("aether-read-large-test");
        Files.writeString(root.resolve("large.txt"), "x".repeat(257 * 1024), StandardCharsets.UTF_8);
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        ToolExecutionResult result = execute(tool, Map.of("file_path", "large.txt"), new ReadFileState());

        assertTrue(result.isError());
        assertTrue(text(result).contains("exceeds maximum allowed size"));
    }

    private ToolExecutionResult execute(ReadTool tool, Map<String, Object> arguments, ReadFileState readFileState) {
        return io.github.lingjiuu.tool.ToolTestSupport.execute(tool, ToolInvocation.builder()
                .toolCall(toolCall("Read", "{}"))
                .arguments(arguments)
                .readFileState(readFileState)
                .build());
    }

    private ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
    }

    private String text(ToolExecutionResult result) {
        assertFalse(result.getContents().isEmpty());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        return ((TextContent) result.getContents().getFirst()).getText();
    }

    private byte[] minimalPngHeader() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
        };
    }
}
