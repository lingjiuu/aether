package io.github.lingjiuu.tool.fs;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileAccessPolicyTest extends TestCase {

    public void testResolveReadablePathAllowsRelativePathUnderRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-file-policy-root");
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "hello");

        Path resolved = FileAccessPolicy.rootedAt(root).resolveReadablePath("notes.txt");

        assertEquals(file.toRealPath(), resolved);
    }

    public void testResolveReadablePathRejectsPathOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-file-policy-root");
        Path outside = Files.createTempFile("aether-outside", ".txt");

        try {
            FileAccessPolicy.rootedAt(root).resolveReadablePath(outside.toString());
            fail("Expected outside path to be rejected.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("outside the allowed root"));
        }
    }
}
