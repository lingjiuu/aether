package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReadToolTest extends TestCase {

    public void testDescriptionNamesCurrentTextOnlyBehavior() {
        ReadTool tool = new ReadTool(FileAccessPolicy.rootedAt(Path.of(".")));
        String description = tool.description();

        assertTrue(description.contains("workspace text file"));
        assertTrue(description.contains("24.0KB"));
        assertTrue(description.contains("offset"));
        assertTrue(description.contains("limit"));
        Map<?, ?> properties = (Map<?, ?>) tool.parametersSchema().get("properties");
        assertTrue(properties.get("path").toString().contains("Workspace file path"));
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
}
