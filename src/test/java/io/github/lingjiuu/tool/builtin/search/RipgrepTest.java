package io.github.lingjiuu.tool.builtin.search;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class RipgrepTest extends TestCase {

    public void testBundledCommandMaterializesCurrentPlatformResource() throws Exception {
        if (Ripgrep.platformKey() == null) {
            return;
        }

        String originalHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("aether-ripgrep-test-home");
        try {
            System.setProperty("user.home", home.toString());

            Optional<String> command = Ripgrep.bundledCommand();

            assertTrue(command.isPresent());
            Path commandPath = Path.of(command.get());
            assertTrue(Files.isRegularFile(commandPath));
            if (!isWindows()) {
                assertTrue(Files.isExecutable(commandPath));
            }
            assertRipgrepVersionWorks(commandPath);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    public void testPlatformKeyUsesBundledResourceNames() {
        String originalOs = System.getProperty("os.name");
        String originalArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "aarch64");
            assertEquals("arm64-darwin", Ripgrep.platformKey());

            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            assertEquals("x64-win32", Ripgrep.platformKey());

            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "x86_64");
            assertEquals("x64-linux", Ripgrep.platformKey());
        } finally {
            System.setProperty("os.name", originalOs);
            System.setProperty("os.arch", originalArch);
        }
    }

    private void assertRipgrepVersionWorks(Path commandPath) throws Exception {
        Process process = new ProcessBuilder(commandPath.toString(), "--version").start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("ripgrep --version timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("stderr: " + stderr, 0, process.exitValue());
        assertTrue(stdout.startsWith("ripgrep "));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
