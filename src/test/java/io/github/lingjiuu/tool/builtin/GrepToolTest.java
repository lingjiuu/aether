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

public class GrepToolTest extends TestCase {

    public void testFindsLiteralRegexAndIgnoreCaseMatches() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-root");
        Files.writeString(root.resolve("notes.txt"), "Alpha\nbeta\n");
        GrepTool tool = new GrepTool(FileAccessPolicy.rootedAt(root));

        ToolResultMessage literal = execute(tool, "{\"pattern\":\"Alpha\",\"literal\":true}");
        ToolResultMessage regex = execute(tool, "{\"pattern\":\"b.t.\"}");
        ToolResultMessage ignoreCase = execute(tool, "{\"pattern\":\"alpha\",\"ignoreCase\":true}");

        assertTrue(MessageContents.text(literal).contains("notes.txt:1: Alpha"));
        assertTrue(MessageContents.text(regex).contains("notes.txt:2: beta"));
        assertTrue(MessageContents.text(ignoreCase).contains("notes.txt:1: Alpha"));
    }

    public void testGlobContextAndNoMatches() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-root");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "before\nneedle\nafter\n");
        Files.writeString(root.resolve("src/App.txt"), "needle\n");
        GrepTool tool = new GrepTool(FileAccessPolicy.rootedAt(root));

        ToolResultMessage result = execute(tool, "{\"pattern\":\"needle\",\"glob\":\"*.java\",\"context\":1}");
        ToolResultMessage noMatches = execute(tool, "{\"pattern\":\"missing\"}");

        String text = MessageContents.text(result);
        assertTrue(text.contains("src/App.java-1- before"));
        assertTrue(text.contains("src/App.java:2: needle"));
        assertTrue(text.contains("src/App.java-3- after"));
        assertFalse(text.contains("src/App.txt"));
        assertEquals("No matches found", MessageContents.text(noMatches));
    }

    public void testTruncatesLongLinesAndReportsLimit() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-root");
        String longLine = "needle " + "x".repeat(ToolOutputLimits.GREP_MAX_LINE_LENGTH + 20);
        Files.writeString(root.resolve("notes.txt"), longLine + "\nneedle second\n");
        GrepTool tool = new GrepTool(FileAccessPolicy.rootedAt(root));

        ToolResultMessage result = execute(tool, "{\"pattern\":\"needle\",\"limit\":1}");

        String text = MessageContents.text(result);
        assertTrue(text.contains("notes.txt:1: needle"));
        assertTrue(text.contains("... [truncated]"));
        assertTrue(text.contains("1 matches limit reached"));
        assertTrue(text.contains("Some lines truncated"));
    }

    public void testRespectsGitignore() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-root");
        Files.writeString(root.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(root.resolve("ignored.txt"), "needle\n");
        Files.writeString(root.resolve("kept.txt"), "needle\n");

        ToolResultMessage result = execute(new GrepTool(FileAccessPolicy.rootedAt(root)), "{\"pattern\":\"needle\"}");

        String text = MessageContents.text(result);
        assertTrue(text.contains("kept.txt"));
        assertFalse(text.contains("ignored.txt"));
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
