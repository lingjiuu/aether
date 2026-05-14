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

public class FindToolTest extends TestCase {

    public void testFindsBasenameAndPathGlobs() throws Exception {
        Path root = Files.createTempDirectory("aether-find-root");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "class App {}");
        Files.writeString(root.resolve("README.md"), "hello");
        FindTool tool = new FindTool(FileAccessPolicy.rootedAt(root));

        ToolResultMessage basename = execute(tool, "{\"pattern\":\"*.java\"}");
        ToolResultMessage path = execute(tool, "{\"pattern\":\"src/**/*.java\"}");

        assertFalse(basename.isError());
        assertTrue(MessageContents.text(basename).contains("src/main/App.java"));
        assertFalse(path.isError());
        assertTrue(MessageContents.text(path).contains("src/main/App.java"));
    }

    public void testRespectsGitignorePatterns() throws Exception {
        Path root = Files.createTempDirectory("aether-find-root");
        Files.writeString(root.resolve(".gitignore"), "ignored.txt\nbuild/\n");
        Files.writeString(root.resolve("ignored.txt"), "ignored");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/output.java"), "ignored");
        Files.writeString(root.resolve("kept.java"), "kept");

        ToolResultMessage result = execute(new FindTool(FileAccessPolicy.rootedAt(root)), "{\"pattern\":\"*.java\"}");

        String text = MessageContents.text(result);
        assertFalse(result.isError());
        assertTrue(text.contains("kept.java"));
        assertFalse(text.contains("build/output.java"));
    }

    public void testNoMatchesAndLimitNotice() throws Exception {
        Path root = Files.createTempDirectory("aether-find-root");
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        FindTool tool = new FindTool(FileAccessPolicy.rootedAt(root));

        ToolResultMessage noMatches = execute(tool, "{\"pattern\":\"*.java\"}");
        ToolResultMessage limited = execute(tool, "{\"pattern\":\"*.txt\",\"limit\":1}");

        assertEquals("No files found matching pattern", MessageContents.text(noMatches));
        assertTrue(MessageContents.text(limited).contains("1 results limit reached"));
    }

    public void testReportsMissingFd() throws Exception {
        Path root = Files.createTempDirectory("aether-find-root");
        FindTool tool = new FindTool(
                FileAccessPolicy.rootedAt(root),
                new ToolBinaryResolver(
                        root.resolve("tools"),
                        command -> false,
                        (binary, toolsDir) -> java.util.Optional.empty(),
                        () -> true
                )
        );

        ToolResultMessage result = execute(tool, "{\"pattern\":\"*.java\"}");

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("fd is not available and could not be downloaded"));
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
