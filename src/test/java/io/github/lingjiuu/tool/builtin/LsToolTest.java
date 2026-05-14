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

public class LsToolTest extends TestCase {

    public void testDescriptionNamesLimits() {
        String description = new LsTool(FileAccessPolicy.rootedAt(Path.of("."))).description();

        assertTrue(description.contains("500 entries"));
        assertTrue(description.contains("50.0KB"));
        assertTrue(description.contains("dotfiles"));
    }

    public void testListsDotfilesSortedAndMarksDirectories() throws Exception {
        Path root = Files.createTempDirectory("aether-ls-root");
        Files.writeString(root.resolve("beta.txt"), "beta");
        Files.writeString(root.resolve(".env"), "secret");
        Files.createDirectory(root.resolve("Alpha"));

        ToolResultMessage result = execute(new LsTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\".\"}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.indexOf(".env") < text.indexOf("Alpha/"));
        assertTrue(text.indexOf("Alpha/") < text.indexOf("beta.txt"));
    }

    public void testReturnsEmptyDirectoryText() throws Exception {
        Path root = Files.createTempDirectory("aether-ls-root");

        ToolResultMessage result = execute(new LsTool(FileAccessPolicy.rootedAt(root)), "{}");

        assertFalse(result.isError());
        assertEquals("(empty directory)", MessageContents.text(result));
    }

    public void testReportsLimitNotice() throws Exception {
        Path root = Files.createTempDirectory("aether-ls-root");
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");

        ToolResultMessage result = execute(new LsTool(FileAccessPolicy.rootedAt(root)), "{\"limit\":1}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.contains("a.txt"));
        assertFalse(text.contains("b.txt"));
        assertTrue(text.contains("1 entries limit reached"));
    }

    public void testRejectsOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-ls-root");
        Path outside = Files.createTempDirectory("aether-ls-outside");

        ToolResultMessage result = execute(new LsTool(FileAccessPolicy.rootedAt(root)), "{\"path\":\"" + outside + "\"}");

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("outside the allowed root"));
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
