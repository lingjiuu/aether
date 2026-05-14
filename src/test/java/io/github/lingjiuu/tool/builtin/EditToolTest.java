package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditToolTest extends TestCase {

    public void testReplacesExactUniqueText() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"beta\",\"newText\":\"delta\"}"
        );

        assertFalse(result.isError());
        assertEquals("alpha\ndelta\ngamma\n", Files.readString(root.resolve("notes.txt")));
        assertTrue(MessageContents.text(result).contains("Successfully replaced text"));
    }

    public void testRejectsMissingOldTextAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"missing\",\"newText\":\"delta\"}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("exact oldText not found"));
        assertEquals("alpha\nbeta\n", Files.readString(file));
    }

    public void testRejectsDuplicateOldTextAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"beta\",\"newText\":\"delta\"}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("matched multiple times"));
        assertEquals("alpha\nbeta\nbeta\n", Files.readString(file));
    }

    public void testRejectsEmptyOldTextAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"\",\"newText\":\"delta\"}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("oldText must not be empty"));
        assertEquals("alpha\nbeta\n", Files.readString(file));
    }

    public void testPreservesCrlfLineEndings() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\r\nbeta\r\ngamma\r\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"beta\",\"newText\":\"delta\"}"
        );

        assertFalse(result.isError());
        assertEquals("alpha\r\ndelta\r\ngamma\r\n", Files.readString(file));
    }

    public void testPreservesLeadingBom() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "\uFEFFalpha\nbeta\n", StandardCharsets.UTF_8);

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"beta\",\"newText\":\"delta\"}"
        );

        assertFalse(result.isError());
        assertEquals("\uFEFFalpha\ndelta\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testRejectsOutsideRootWithUserDenialText() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path outside = Files.createTempFile("aether-edit-outside", ".txt");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"" + outside + "\",\"oldText\":\"old\",\"newText\":\"new\"}"
        );

        assertTrue(result.isError());
        assertEquals("用户拒绝了此次调用", MessageContents.text(result));
    }

    public void testResultDetailsContainUsefulDiff() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"beta\",\"newText\":\"delta\"}"
        );

        assertFalse(result.isError());
        assertTrue(result.getDetails() instanceof Map<?, ?>);
        Map<?, ?> details = (Map<?, ?>) result.getDetails();
        assertEquals(1, details.get("replacements"));
        assertEquals(2, details.get("firstChangedLine"));
        assertTrue(String.valueOf(details.get("diff")).contains("-beta"));
        assertTrue(String.valueOf(details.get("diff")).contains("+delta"));
    }

    public void testRenderCallShowsPath() {
        EditTool tool = new EditTool(FileAccessPolicy.rootedAt(Path.of(".")));
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("edit")
                .argumentsJson("{}")
                .build();

        String rendered = tool.renderCall(ToolRenderRequest.forCall(
                toolCall,
                Map.of("path", "notes.txt")
        )).text();

        assertEquals("edit notes.txt", rendered);
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
