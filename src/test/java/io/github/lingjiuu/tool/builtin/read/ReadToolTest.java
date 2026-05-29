package io.github.lingjiuu.tool.builtin.read;

import io.github.lingjiuu.message.content.ImageContent;
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

public class ReadToolTest extends TestCase {

    public void testReadTextFileWithoutLimitOmitsLimitFromDetails() throws Exception {
        Path root = Files.createTempDirectory("aether-read-tool-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello world\nsecond line", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = tool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\"}"))
                .arguments(Map.of("file_path", "hello.txt"))
                .readFileState(readFileState)
                .build());

        assertFalse(result.isError());
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().getFirst() instanceof TextContent);
        assertEquals("     1\thello world\n     2\tsecond line", ((TextContent) result.getContents().getFirst()).getText());

        assertTrue(result.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertEquals("read", details.get("kind"));
        assertEquals("hello.txt", details.get("path"));
        assertEquals(1, ((Number) details.get("offset")).intValue());
        assertFalse(details.containsKey("limit"));
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
            tool.validateArguments("{\"path\":\"hello.txt\"}");
        } catch (IllegalArgumentException e) {
            error = e;
        }

        assertNotNull(error);
        assertTrue(error.getMessage().contains("Missing required tool argument: file_path")
                || error.getMessage().contains("Unknown tool argument: path"));
    }

    public void testPartialReadRecordsPartialState() throws Exception {
        Path root = Files.createTempDirectory("aether-read-partial-state-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = tool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        assertFalse(result.isError());
        assertEquals("     1\thello\n\n[1 more lines in file. Use offset=2 to continue.]",
                ((TextContent) result.getContents().getFirst()).getText());
        assertNotNull(readFileState.get(file));
        assertTrue(readFileState.get(file).partial());
    }

    public void testPartialReadDoesNotDowngradeExistingFullReadState() throws Exception {
        Path root = Files.createTempDirectory("aether-read-preserve-full-state-test");
        Path file = root.resolve("hello.txt");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        ReadFileState readFileState = new ReadFileState();
        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));

        tool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\"}"))
                .arguments(Map.of("file_path", "hello.txt"))
                .readFileState(readFileState)
                .build());
        tool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"hello.txt\",\"limit\":1}"))
                .arguments(Map.of("file_path", "hello.txt", "limit", 1))
                .readFileState(readFileState)
                .build());

        assertNotNull(readFileState.get(file));
        assertFalse(readFileState.get(file).partial());
        assertEquals("hello\nworld", readFileState.get(file).content());
    }

    public void testImageReadDoesNotRecordTextReadState() throws Exception {
        Path root = Files.createTempDirectory("aether-read-image-test");
        Path file = root.resolve("image.png");
        Files.write(file, minimalPngHeader());
        ReadFileState readFileState = new ReadFileState();

        ReadTool tool = new ReadTool(WorkspaceAccessPolicy.rootedAt(root));
        ToolExecutionResult result = tool.execute(ToolInvocation.builder()
                .toolCall(toolCall("read", "{\"file_path\":\"image.png\"}"))
                .arguments(Map.of("file_path", "image.png"))
                .readFileState(readFileState)
                .build());

        assertFalse(result.isError());
        assertEquals(2, result.getContents().size());
        assertTrue(result.getContents().get(0) instanceof TextContent);
        assertTrue(result.getContents().get(1) instanceof ImageContent);
        assertNull(readFileState.get(file));
    }

    private ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
    }

    private byte[] minimalPngHeader() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
        };
    }
}
