package io.github.lingjiuu.tool.result;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ToolArtifactStoreTest extends TestCase {

    public void testPersistTextWritesStableArtifactAndPreview() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-artifacts-test");
        ToolArtifactStore store = new ToolArtifactStore(root.resolve("tool-results"));
        String content = "alpha\nbeta\ngamma";

        PersistedToolOutput first = store.persistText("call/1", "output", content, ToolResultPreviewMode.HEAD);
        PersistedToolOutput second = store.persistText("call/1", "output", "different", ToolResultPreviewMode.HEAD);

        assertEquals(first.path(), second.path());
        assertEquals(content, Files.readString(first.path(), StandardCharsets.UTF_8));
        assertEquals(content.length(), first.originalSizeBytes());
        assertEquals("alpha\nbeta\ngamma", first.preview());
        assertFalse(first.hasMore());
    }

    public void testPersistTextFileCopiesSource() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-artifacts-test");
        Path source = root.resolve("source.log");
        Files.writeString(source, "full output", StandardCharsets.UTF_8);
        ToolArtifactStore store = new ToolArtifactStore(root.resolve("tool-results"));

        PersistedToolOutput output = store.persistTextFile("call-2", "output", source, ToolResultPreviewMode.HEAD);

        assertEquals("full output", Files.readString(output.path(), StandardCharsets.UTF_8));
        assertEquals("full output", output.preview());
    }
}
