package io.github.lingjiuu.tool.fs;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceIgnoreMatcherTest extends TestCase {

    public void testMatchesCommonGitignorePatterns() throws Exception {
        Path root = Files.createTempDirectory("aether-ignore-root");
        Files.writeString(root.resolve(".gitignore"), """
                ignored.txt
                build/
                *.class
                target/generated/**
                """);
        Files.createDirectories(root.resolve("build"));
        Files.createDirectories(root.resolve("target/generated"));
        WorkspaceIgnoreMatcher matcher = WorkspaceIgnoreMatcher.load(root);

        assertTrue(matcher.isIgnored(root.resolve("ignored.txt"), false));
        assertTrue(matcher.isIgnored(root.resolve("nested/ignored.txt"), false));
        assertTrue(matcher.isIgnored(root.resolve("build/output.txt"), false));
        assertTrue(matcher.isIgnored(root.resolve("Main.class"), false));
        assertTrue(matcher.isIgnored(root.resolve("target/generated/A.java"), false));
        assertFalse(matcher.isIgnored(root.resolve("src/Main.java"), false));
    }

    public void testAlwaysIgnoresGitAndNodeModules() throws Exception {
        Path root = Files.createTempDirectory("aether-ignore-root");
        WorkspaceIgnoreMatcher matcher = WorkspaceIgnoreMatcher.load(root);

        assertTrue(matcher.isIgnored(root.resolve(".git/config"), false));
        assertTrue(matcher.isIgnored(root.resolve("node_modules/pkg/index.js"), false));
    }
}
