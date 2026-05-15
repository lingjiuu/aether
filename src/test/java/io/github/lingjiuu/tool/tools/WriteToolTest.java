package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.runtime.ToolRunner;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class WriteToolTest extends TestCase {

    public void testCreatesFile() throws Exception {
        Path root = Files.createTempDirectory("aether-write-root");

        ToolResultMessage result = execute(
                new WriteTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"content\":\"hello\"}"
        );

        assertFalse(result.isError());
        assertEquals("hello", Files.readString(root.resolve("notes.txt")));
        assertTrue(MessageContents.text(result).contains("Successfully wrote 5 chars"));
    }

    public void testOverwritesFile() throws Exception {
        Path root = Files.createTempDirectory("aether-write-root");
        Files.writeString(root.resolve("notes.txt"), "old");

        ToolResultMessage result = execute(
                new WriteTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"content\":\"new\"}"
        );

        assertFalse(result.isError());
        assertEquals("new", Files.readString(root.resolve("notes.txt")));
        assertTrue(result.getDetails() instanceof Map<?, ?>);
        assertEquals(true, ((Map<?, ?>) result.getDetails()).get("existedBefore"));
    }

    public void testCreatesParentDirectories() throws Exception {
        Path root = Files.createTempDirectory("aether-write-root");

        ToolResultMessage result = execute(
                new WriteTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"path\":\"nested/dir/notes.txt\",\"content\":\"hello\"}"
        );

        assertFalse(result.isError());
        assertEquals("hello", Files.readString(root.resolve("nested/dir/notes.txt")));
    }

    public void testRejectsOutsideRootWithUserDenialText() throws Exception {
        Path root = Files.createTempDirectory("aether-write-root");
        Path outside = Files.createTempFile("aether-write-outside", ".txt");

        ToolResultMessage result = execute(
                new WriteTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"path\":\"" + outside + "\",\"content\":\"hello\"}"
        );

        assertTrue(result.isError());
        assertEquals("用户拒绝了此次调用", MessageContents.text(result));
    }

    public void testRejectsUnknownFieldThroughToolRunner() throws Exception {
        Path root = Files.createTempDirectory("aether-write-root");

        ToolResultMessage result = execute(
                new WriteTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"content\":\"hello\",\"extra\":true}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Unknown tool argument: extra"));
    }

    public void testRejectsWrongContentTypeThroughToolRunner() throws Exception {
        Path root = Files.createTempDirectory("aether-write-root");

        ToolResultMessage result = execute(
                new WriteTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"content\":42}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("content must be a string"));
    }

    public void testRenderCallShowsPathAndContentLength() {
        WriteTool tool = new WriteTool(WorkspaceAccessPolicy.rootedAt(Path.of(".")));
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("write")
                .argumentsJson("{}")
                .build();

        String rendered = tool.renderCall(ToolRenderRequest.forCall(
                toolCall,
                Map.of("path", "notes.txt", "content", "hello")
        )).text();

        assertEquals("write notes.txt (5 chars)", rendered);
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
