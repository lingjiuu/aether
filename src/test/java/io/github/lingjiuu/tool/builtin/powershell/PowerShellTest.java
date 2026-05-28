package io.github.lingjiuu.tool.builtin.powershell;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class PowerShellTest extends TestCase {

    public void testEncodedCommandUsesUtf16LePowerShellScript() {
        String encoded = PowerShell.encodedCommand("Write-Output 'hello'");
        String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);

        assertTrue(decoded.contains("Write-Output 'hello'"));
        assertFalse(decoded.contains("-NoProfile"));
        assertTrue(decoded.contains("$LASTEXITCODE"));
        assertTrue(decoded.contains("exit $_aetherExitCode"));
    }
}
