package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReadToolTest extends TestCase {

    public void testDescriptionMentionsImages() {
        ReadTool tool = new ReadTool(FileAccessPolicy.rootedAt(Path.of(".")));
        String description = tool.description();

        assertTrue(description.contains("workspace file"));
        assertTrue(description.contains("images (jpg, png, gif, webp)"));
        assertTrue(description.contains("24.0KB"));
        assertTrue(description.contains("offset"));
        assertTrue(description.contains("limit"));
    }

    public void testReadsSmallTextFile() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"notes.txt\"}");

        assertFalse(result.isError());
        assertEquals("alpha\nbeta\ngamma", MessageContents.text(result));
    }

    public void testReadsOffsetLimitAndReportsContinuation() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(
                new ReadTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"offset\":2,\"limit\":1}"
        );

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.contains("beta"));
        assertFalse(text.contains("alpha"));
        assertFalse(text.contains("gamma\n"));
        assertTrue(text.contains("Use offset=3 to continue"));
    }

    public void testRejectsOffsetBeyondEndOfFile() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new ReadTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"offset\":5}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Offset 5 is beyond end of file"));
        assertTrue(MessageContents.text(result).contains("3 lines total"));
    }

    public void testTruncatesLargeFileWithContinuation() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        String line = "x".repeat(100);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            content.append(line).append('\n');
        }
        Files.writeString(root.resolve("large.txt"), content.toString());

        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"large.txt\"}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.length() < content.length());
        assertTrue(text.contains("Use offset="));
        assertTrue(text.contains("to continue"));
    }

    public void testReportsFirstLineTooLarge() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("large-line.txt"), "x".repeat(ToolOutputLimits.READ_MAX_BYTES + 10));

        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"large-line.txt\"}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.contains("Line 1 exceeds"));
        assertTrue(text.contains("limit"));
    }

    public void testRejectsDirectories() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.createDirectory(root.resolve("src"));

        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"src\"}");

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Not a file"));
    }

    public void testRejectsOutsideRootWithUserDenialText() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Path outside = Files.createTempFile("aether-read-outside", ".txt");

        ToolResultMessage result = execute(
                new ReadTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"" + outside + "\"}"
        );

        assertTrue(result.isError());
        assertEquals("用户拒绝了此次调用", MessageContents.text(result));
    }

    public void testReadsSupportedImageFile() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Path image = root.resolve("pixel.png");
        Files.write(image, tinyPngBytes());

        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"pixel.png\"}");

        assertFalse(result.isError());
        assertTrue(MessageContents.text(result).contains("Read image file [image/png]"));
        assertEquals(2, result.getContents().size());
        assertTrue(result.getContents().get(1) instanceof ImageContent);
        ImageContent imageContent = (ImageContent) result.getContents().get(1);
        assertEquals("image/png", imageContent.getMimeType());
        assertFalse(imageContent.getData().isBlank());
        assertTrue(result.getDetails() instanceof Map<?, ?>);
        assertEquals(true, ((Map<?, ?>) result.getDetails()).get("image"));
        assertEquals(false, ((Map<?, ?>) result.getDetails()).get("omitted"));
    }

    public void testOmitsOversizedImagePayload() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.write(root.resolve("huge.png"), hugePngBytes());

        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"huge.png\"}");

        assertFalse(result.isError());
        assertTrue(MessageContents.text(result).contains("inline image exceeds"));
        assertEquals(1, result.getContents().size());
        assertTrue(result.getContents().get(0).getClass().getSimpleName().contains("TextContent"));
        assertEquals(true, ((Map<?, ?>) result.getDetails()).get("image"));
        assertEquals(true, ((Map<?, ?>) result.getDetails()).get("omitted"));
    }

    public void testRenderResultRemainsTextOnly() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\n");
        ToolResultMessage result = execute(new ReadTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"notes.txt\"}");

        assertEquals("alpha", MessageContents.text(result));
    }

    private ToolResultMessage execute(ToolDefinition tool, String argumentsJson) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(tool.name())
                .argumentsJson(argumentsJson)
                .build();
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .contents(List.of(toolCall))
                .build();
        return new ToolRunner(registry).run(assistantMessage, toolCall, null);
    }

    private byte[] tinyPngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d,
                0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00,
                (byte) 0x90, 0x77, 0x53, (byte) 0xde,
                0x00, 0x00, 0x00, 0x0a,
                0x49, 0x44, 0x41, 0x54,
                0x08, (byte) 0xd7, 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
                (byte) 0xe2, 0x21, (byte) 0xbc, 0x33,
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4e, 0x44,
                (byte) 0xae, 0x42, 0x60, (byte) 0x82
        };
    }

    private byte[] hugePngBytes() {
        byte[] bytes = new byte[3_600_000];
        byte[] tiny = tinyPngBytes();
        System.arraycopy(tiny, 0, bytes, 0, Math.min(tiny.length, bytes.length));
        bytes[33] = 0x00;
        bytes[34] = 0x00;
        bytes[35] = 0x00;
        bytes[36] = 0x00;
        bytes[37] = 0x49;
        bytes[38] = 0x44;
        bytes[39] = 0x41;
        bytes[40] = 0x54;
        return bytes;
    }
}
