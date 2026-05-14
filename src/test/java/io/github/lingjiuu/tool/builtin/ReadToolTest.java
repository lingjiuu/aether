package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReadToolTest extends TestCase {

    public void testReadUsesOffsetLimitAndContinuationHint() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("notes.txt"), "one\ntwo\nthree\nfour\n");
        ReadTool tool = new ReadTool(FileAccessPolicy.rootedAt(root), new ReadToolLimits(10, 4096));

        ToolResultMessage result = execute(tool, "{\"path\":\"notes.txt\",\"offset\":2,\"limit\":2}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.contains("2\ttwo"));
        assertTrue(text.contains("3\tthree"));
        assertFalse(text.contains("1\tone"));
        assertTrue(text.contains("Use offset=4 to continue."));
    }

    public void testReadBoundsOutputByConfiguredLineLimit() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Files.writeString(root.resolve("notes.txt"), "one\ntwo\nthree\n");
        ReadTool tool = new ReadTool(FileAccessPolicy.rootedAt(root), new ReadToolLimits(2, 4096));

        ToolResultMessage result = execute(tool, "{\"path\":\"notes.txt\"}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.contains("1\tone"));
        assertTrue(text.contains("2\ttwo"));
        assertFalse(text.contains("3\tthree"));
        assertTrue(text.contains("Use offset=3 to continue."));
    }

    public void testReadOutsideRootReturnsErrorResult() throws Exception {
        Path root = Files.createTempDirectory("aether-read-root");
        Path outside = Files.createTempFile("aether-read-outside", ".txt");
        Files.writeString(outside, "secret");
        ReadTool tool = new ReadTool(FileAccessPolicy.rootedAt(root));

        ToolResultMessage result = execute(tool, "{\"path\":\"" + outside + "\"}");

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("outside the allowed root"));
    }

    private ToolResultMessage execute(ReadTool tool, String argumentsJson) {
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("read")
                .argumentsJson(argumentsJson)
                .build();
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .contents(List.of(toolCall))
                .build();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return new ToolRunner(registry).run(assistantMessage, toolCall, null);
    }
}
