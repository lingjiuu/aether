package io.github.lingjiuu.tool.builtin.powershell;

import io.github.lingjiuu.tool.builtin.ExecutableFinder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class PowerShell {

    private PowerShell() {
    }

    public static boolean isAvailable() {
        return command().isPresent();
    }

    public static Optional<String> command() {
        if (!isWindows()) {
            return Optional.empty();
        }
        Optional<String> pwsh = ExecutableFinder.findOnPath("pwsh.exe");
        if (pwsh.isPresent()) {
            return pwsh;
        }
        return ExecutableFinder.findOnPath("powershell.exe");
    }

    static Optional<List<String>> commandLine(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        return command().map(path -> List.of(
                path,
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                encodedCommand(command)
        ));
    }

    static String encodedCommand(String command) {
        String script = """
                [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
                $OutputEncoding = [Console]::OutputEncoding
                %s
                $_aetherExitCode = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($?) { 0 } else { 1 }
                exit $_aetherExitCode
                """.formatted(command);
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
