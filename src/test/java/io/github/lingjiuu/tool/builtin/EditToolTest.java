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

    public void testPublicSchemaUsesEditsArrayOnly() {
        EditTool tool = new EditTool(FileAccessPolicy.rootedAt(Path.of(".")));

        Map<?, ?> schema = tool.parametersSchema();
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");

        assertTrue(properties.containsKey("edits"));
        assertFalse(properties.containsKey("oldText"));
        assertFalse(properties.containsKey("newText"));
    }

    public void testReplacesExactUniqueTextWithEditsArray() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"beta\",\"newText\":\"delta\"}]}"
        );

        assertFalse(result.isError());
        assertEquals("alpha\ndelta\ngamma\n", Files.readString(root.resolve("notes.txt")));
        assertTrue(MessageContents.text(result).contains("Successfully replaced 1 block(s) in notes.txt."));
    }

    public void testAppliesMultipleDisjointEditsInOneFile() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"alpha\",\"newText\":\"one\"},{\"oldText\":\"gamma\",\"newText\":\"three\"}]}"
        );

        assertFalse(result.isError());
        assertEquals("one\nbeta\nthree\n", Files.readString(root.resolve("notes.txt")));
        assertTrue(MessageContents.text(result).contains("Successfully replaced 2 block(s) in notes.txt."));
    }

    public void testEditsMatchOriginalContentNotIncrementally() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "a b c\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"},{\"oldText\":\"b\",\"newText\":\"c\"}]}"
        );

        assertFalse(result.isError());
        assertEquals("b c c\n", Files.readString(file));
    }

    public void testRejectsOverlappingEditsAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        String original = "abcd\n";
        Files.writeString(file, original);

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"abc\",\"newText\":\"x\"},{\"oldText\":\"bcd\",\"newText\":\"y\"}]}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("overlap"));
        assertEquals(original, Files.readString(file));
    }

    public void testRejectsEmptyEditsArrayAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        String original = "alpha\n";
        Files.writeString(file, original);

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[]}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("edits must contain at least one replacement"));
        assertEquals(original, Files.readString(file));
    }

    public void testRejectsMissingOldTextAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"missing\",\"newText\":\"delta\"}]}"
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
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"beta\",\"newText\":\"delta\"}]}"
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
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"\",\"newText\":\"delta\"}]}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("oldText must not be empty"));
        assertEquals("alpha\nbeta\n", Files.readString(file));
    }

    public void testParsesStringifiedEdits() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":\"[{\\\"oldText\\\":\\\"beta\\\",\\\"newText\\\":\\\"delta\\\"}]\"}"
        );

        assertFalse(result.isError());
        assertEquals("alpha\ndelta\n", Files.readString(file));
    }

    public void testMalformedStringifiedEditsFailsValidationAndLeavesFileUnchanged() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        String original = "alpha\nbeta\n";
        Files.writeString(file, original);

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":\"not json\"}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("edits must be an array"));
        assertEquals(original, Files.readString(file));
    }

    public void testFoldsTopLevelOldTextNewTextIntoEdits() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"oldText\":\"beta\",\"newText\":\"delta\"}"
        );

        assertFalse(result.isError());
        assertEquals("alpha\ndelta\n", Files.readString(file));
    }

    public void testTopLevelOldTextNewTextAppendToExistingEdits() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"alpha\",\"newText\":\"one\"}],\"oldText\":\"gamma\",\"newText\":\"three\"}"
        );

        assertFalse(result.isError());
        assertEquals("one\nbeta\nthree\n", Files.readString(file));
    }

    public void testPreservesCrlfLineEndings() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "alpha\r\nbeta\r\ngamma\r\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"beta\",\"newText\":\"delta\"}]}"
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
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"beta\",\"newText\":\"delta\"}]}"
        );

        assertFalse(result.isError());
        assertEquals("\uFEFFalpha\ndelta\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    public void testRejectsOutsideRootWithUserDenialText() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Path outside = Files.createTempFile("aether-edit-outside", ".txt");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"" + outside + "\",\"edits\":[{\"oldText\":\"old\",\"newText\":\"new\"}]}"
        );

        assertTrue(result.isError());
        assertEquals("用户拒绝了此次调用", MessageContents.text(result));
    }

    public void testResultDetailsContainUsefulDiff() throws Exception {
        Path root = Files.createTempDirectory("aether-edit-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");

        ToolResultMessage result = execute(
                new EditTool(FileAccessPolicy.rootedAt(root)),
                "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"alpha\",\"newText\":\"one\"},{\"oldText\":\"gamma\",\"newText\":\"three\"}]}"
        );

        assertFalse(result.isError());
        assertTrue(result.getDetails() instanceof Map<?, ?>);
        Map<?, ?> details = (Map<?, ?>) result.getDetails();
        assertEquals(2, details.get("replacements"));
        assertEquals(1, details.get("firstChangedLine"));
        String diff = String.valueOf(details.get("diff"));
        assertTrue(diff.contains("-alpha"));
        assertTrue(diff.contains("+one"));
        assertTrue(diff.contains("-gamma"));
        assertTrue(diff.contains("+three"));
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
