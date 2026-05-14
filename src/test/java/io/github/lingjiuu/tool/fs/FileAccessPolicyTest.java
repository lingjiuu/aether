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

    public void testResolveWritablePathAllowsRelativePathUnderRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-file-policy-root");
        FileAccessPolicy policy = FileAccessPolicy.rootedAt(root);
        Path resolved = policy.resolveWritablePath("notes.txt");

        assertEquals(policy.root().resolve("notes.txt").toAbsolutePath().normalize(), resolved);
    }

    public void testResolveWritablePathAllowsNestedMissingFileUnderRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-file-policy-root");
        FileAccessPolicy policy = FileAccessPolicy.rootedAt(root);
        Path resolved = policy.resolveWritablePath("nested/dir/notes.txt");

        assertEquals(policy.root().resolve("nested/dir/notes.txt").toAbsolutePath().normalize(), resolved);
    }

    public void testResolveWritablePathRejectsAbsolutePathOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-file-policy-root");
        Path outside = Files.createTempFile("aether-outside", ".txt");

        try {
            FileAccessPolicy.rootedAt(root).resolveWritablePath(outside.toString());
            fail("Expected outside path to be rejected.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("outside the allowed root"));
        }
    }

    public void testResolveWritablePathRejectsSymlinkParentOutsideRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-file-policy-root");
        Path outside = Files.createTempDirectory("aether-outside");
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        try {
            FileAccessPolicy.rootedAt(root).resolveWritablePath("link/notes.txt");
            fail("Expected symlink parent outside root to be rejected.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("outside the allowed root"));
        }
    }
}
