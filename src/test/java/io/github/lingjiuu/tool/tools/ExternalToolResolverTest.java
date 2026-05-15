package io.github.lingjiuu.tool.tools;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ExternalToolResolverTest extends TestCase {

    public void testManagedBinaryWinsBeforePath() throws Exception {
        Path toolsDir = Files.createTempDirectory("aether-tools");
        Path managedFd = toolsDir.resolve(ExternalTool.FD.executableName(System.getProperty("os.name")));
        Files.writeString(managedFd, "fd");
        List<String> probed = new ArrayList<>();

        ExternalToolResolver resolver = new ExternalToolResolver(
                toolsDir,
                command -> {
                    probed.add(command);
                    return true;
                },
                (binary, dir) -> Optional.empty(),
                () -> false
        );

        assertEquals(managedFd.toAbsolutePath().normalize().toString(), resolver.resolve(ExternalTool.FD).orElseThrow());
        assertTrue(probed.isEmpty());
    }

    public void testPathCandidateIsAccepted() throws Exception {
        Path toolsDir = Files.createTempDirectory("aether-tools");
        List<String> probed = new ArrayList<>();

        ExternalToolResolver resolver = new ExternalToolResolver(
                toolsDir,
                command -> {
                    probed.add(command);
                    return "fdfind".equals(command);
                },
                (binary, dir) -> Optional.empty(),
                () -> false
        );

        assertEquals("fdfind", resolver.resolve(ExternalTool.FD).orElseThrow());
        assertEquals(List.of("fd", "fdfind"), probed);
    }

    public void testOfflineSkipsDownload() throws Exception {
        Path toolsDir = Files.createTempDirectory("aether-tools");
        boolean[] downloaded = {false};

        ExternalToolResolver resolver = new ExternalToolResolver(
                toolsDir,
                command -> false,
                (binary, dir) -> {
                    downloaded[0] = true;
                    return Optional.empty();
                },
                () -> true
        );

        assertTrue(resolver.resolve(ExternalTool.RG).isEmpty());
        assertFalse(downloaded[0]);
    }

    public void testDownloadUsedWhenMissing() throws Exception {
        Path toolsDir = Files.createTempDirectory("aether-tools");
        Path downloadedRg = toolsDir.resolve(ExternalTool.RG.executableName(System.getProperty("os.name")));

        ExternalToolResolver resolver = new ExternalToolResolver(
                toolsDir,
                command -> false,
                (binary, dir) -> Optional.of(downloadedRg),
                () -> false
        );

        assertEquals(downloadedRg.toAbsolutePath().normalize().toString(), resolver.resolve(ExternalTool.RG).orElseThrow());
    }

    public void testMissingReturnsEmpty() throws Exception {
        Path toolsDir = Files.createTempDirectory("aether-tools");
        ExternalToolResolver resolver = new ExternalToolResolver(
                toolsDir,
                command -> false,
                (binary, dir) -> Optional.empty(),
                () -> false
        );

        assertTrue(resolver.resolve(ExternalTool.RG).isEmpty());
    }
}
