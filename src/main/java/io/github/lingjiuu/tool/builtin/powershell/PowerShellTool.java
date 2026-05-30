package io.github.lingjiuu.tool.builtin.powershell;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.builtin.shell.ShellOutputCapture;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PowerShellTool implements Tool<PowerShellTool.Input, PowerShellTool.Output> {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MAX_TIMEOUT_MS = 600_000;

    private final Path workspaceRoot;

    public PowerShellTool(WorkspaceAccessPolicy accessPolicy) {
        this(accessPolicy == null ? null : accessPolicy.root());
    }

    public PowerShellTool(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "PowerShell";
    }

    @Override
    public String label() {
        return "PowerShell";
    }

    @Override
    public String description() {
        String prompt = """
                Executes a given PowerShell command with optional timeout. Shell state (variables, functions) does not persist between commands.

                IMPORTANT: This tool is for terminal operations via PowerShell: git, npm, docker, and PS cmdlets. DO NOT use it for file operations (reading, writing, editing, searching, finding files) - use the specialized tools for this instead.

                __POWERSHELL_EDITION_SECTION__

                Before executing the command, please follow these steps:

                1. Directory Verification:
                   - If the command will create new directories or files, first use `Get-ChildItem` (or `ls`) to verify the parent directory exists and is the correct location

                2. Command Execution:
                   - Always quote file paths that contain spaces with double quotes
                   - Capture the output of the command.

                PowerShell Syntax Notes:
                   - Variables use $ prefix: $myVar = "value"
                   - Escape character is backtick (`), not backslash
                   - Use Verb-Noun cmdlet naming: Get-ChildItem, Set-Location, New-Item, Remove-Item
                   - Common aliases: ls (Get-ChildItem), cd (Set-Location), cat (Get-Content), rm (Remove-Item)
                   - Pipe operator | works similarly to bash but passes objects, not text
                   - Use Select-Object, Where-Object, ForEach-Object for filtering and transformation
                   - String interpolation: "Hello $name" or "Hello $($obj.Property)"
                   - Registry access uses PSDrive prefixes: `HKLM:\\SOFTWARE\\...`, `HKCU:\\...` - NOT raw `HKEY_LOCAL_MACHINE\\...`
                   - Environment variables: read with `$env:NAME`, set with `$env:NAME = "value"` (NOT `Set-Variable` or bash `export`)
                   - Call native exe with spaces in path via call operator: `& "C:\\Program Files\\App\\app.exe" arg1 arg2`

                Interactive and blocking commands (will hang - this tool runs with -NonInteractive):
                   - NEVER use `Read-Host`, `Get-Credential`, `Out-GridView`, `$Host.UI.PromptForChoice`, or `pause`
                   - Destructive cmdlets (`Remove-Item`, `Stop-Process`, `Clear-Content`, etc.) may prompt for confirmation. Add `-Confirm:$false` when you intend the action to proceed. Use `-Force` for read-only/hidden items.
                   - Never use `git rebase -i`, `git add -i`, or other commands that open an interactive editor

                Passing multiline strings (commit messages, file content) to native executables:
                   - Use a single-quoted here-string so PowerShell does not expand `$` or backticks inside. The closing `'@` MUST be at column 0 (no leading whitespace) on its own line - indenting it is a parse error:
                <example>
                git commit -m @'
                Commit message here.
                Second line with $literal dollar signs.
                '@
                </example>
                   - Use `@'...'@` (single-quoted, literal) not `@"..."@` (double-quoted, interpolated) unless you need variable expansion
                   - For arguments containing `-`, `@`, or other characters PowerShell parses as operators, use the stop-parsing token: `git log --% --format=%H`

                Usage notes:
                  - The command argument is required.
                  - You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). If not specified, commands will timeout after 120000ms (2 minutes).
                  - If the output exceeds 30000 characters, output will be truncated before being returned to you.
                  - Avoid using PowerShell to run commands that have dedicated tools, unless explicitly instructed:
                    - File search: Use Glob (NOT Get-ChildItem -Recurse)
                    - Content search: Use Grep (NOT Select-String)
                    - Read files: Use Read (NOT Get-Content)
                    - Edit files: Use Edit
                    - Write files: Use Write (NOT Set-Content/Out-File)
                    - Communication: Output text directly (NOT Write-Output/Write-Host)
                  - When issuing multiple commands:
                    - If the commands are independent and can run in parallel, make multiple PowerShell tool calls in a single message.
                    - If the commands depend on each other and must run sequentially, chain them in a single PowerShell call (see edition-specific chaining syntax above).
                    - Use `;` only when you need to run commands sequentially but don't care if earlier commands fail.
                    - DO NOT use newlines to separate commands (newlines are ok in quoted strings and here-strings)
                  - Do NOT prefix commands with `cd` or `Set-Location` -- the working directory is already set to the correct project directory automatically.
                  - Avoid unnecessary `Start-Sleep` commands:
                    - Do not sleep between commands that can run immediately - just run them.
                    - Do not retry failing commands in a sleep loop - diagnose the root cause or consider an alternative approach.
                    - If you must poll an external process, use a check command rather than sleeping first.
                    - If you must sleep, keep the duration short (1-5 seconds) to avoid blocking the user.
                  - For git commands:
                    - Prefer to create a new commit rather than amending an existing commit.
                    - Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), consider whether there is a safer alternative that achieves the same goal. Only use destructive operations when they are truly the best approach.
                    - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.\
                """;
        return prompt.replace("__POWERSHELL_EDITION_SECTION__", powerShellEditionSection());
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "The PowerShell command to execute"),
                        "timeout", Map.of("type", "number", "minimum", 1, "maximum", MAX_TIMEOUT_MS, "description", "Optional timeout in milliseconds (max 600000)")
                ),
                "required", List.of("command"),
                "additionalProperties", false
        );
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "stdout", Map.of("type", "string", "description", "The standard output of the command"),
                        "stderr", Map.of("type", "string", "description", "The standard error output of the command"),
                        "interrupted", Map.of("type", "boolean", "description", "Whether the command was interrupted"),
                        "returnCodeInterpretation", Map.of("type", "string", "description", "Semantic interpretation for non-error exit codes with special meaning"),
                        "isImage", Map.of("type", "boolean", "description", "Flag to indicate if stdout contains image data"),
                        "persistedOutputPath", Map.of("type", "string", "description", "Path to persisted full output when too large for inline"),
                        "persistedOutputSize", Map.of("type", "number", "description", "Total output size in bytes when persisted")
                ),
                "required", List.of("stdout", "stderr", "interrupted")
        );
    }

    @Override
    public Object prepareInput(Object arguments) {
        if (!(arguments instanceof Map<?, ?> map)) {
            return arguments;
        }
        Map<String, Object> prepared = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if ("timeout".equals(key) && value instanceof String stringValue
                    && stringValue.matches("-?\\d+(\\.\\d+)?")) {
                value = Double.parseDouble(stringValue);
            }
            prepared.put(key, value);
        }
        return prepared;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.EXEC;
    }

    @Override
    public ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.withMaxResultSizeChars(30_000);
    }

    @Override
    public Input parseInput(String argumentsJson) {
        return Input.from(validateInputJson(argumentsJson));
    }

    @Override
    public Map<String, Object> permissionArguments(Input input) {
        return Map.of("command", input.command());
    }

    @Override
    public ToolCallResult<Output> call(Input input, ToolUseContext context) {
        Instant startedAt = Instant.now();
        Process process = null;
        Thread stdoutReaderThread = null;
        Thread stderrReaderThread = null;
        try {
            context.throwIfCancellationRequested();
            String command = input.command();
            Optional<List<String>> shellCommand = PowerShell.commandLine(command);
            if (shellCommand.isEmpty()) {
                return ToolCallResult.failure(ToolFailure.runtime("PowerShell is not available on this system."));
            }
            ShellOutputCapture output = new ShellOutputCapture("aether-powershell");
            ProcessBuilder builder = new ProcessBuilder(shellCommand.get())
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(false);
            process = builder.start();
            Process runningProcess = process;
            CountDownLatch readerDone = new CountDownLatch(2);
            AtomicReference<Exception> readerError = new AtomicReference<>();
            stdoutReaderThread = new Thread(
                    () -> readOutput(
                            runningProcess.getInputStream(),
                            output::appendStdout,
                            output,
                            context,
                            command,
                            readerDone,
                            readerError
                    ),
                    "aether-powershell-stdout"
            );
            stderrReaderThread = new Thread(
                    () -> readOutput(
                            runningProcess.getErrorStream(),
                            output::appendStderr,
                            output,
                            context,
                            command,
                            readerDone,
                            readerError
                    ),
                    "aether-powershell-stderr"
            );
            stdoutReaderThread.setDaemon(true);
            stderrReaderThread.setDaemon(true);
            stdoutReaderThread.start();
            stderrReaderThread.start();

            try (AutoCloseable ignored = context.cancellationToken().onCancel(() -> destroyProcessTree(runningProcess))) {
                boolean finished = process.waitFor(input.timeoutMs(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    destroyProcessTree(process);
                    readerDone.await(1, TimeUnit.SECONDS);
                    ShellOutputCapture.Snapshot snapshot = output.snapshot(true);
                    return ToolCallResult.failedOutput(result(command, null, startedAt, snapshot,
                            true, "Command timed out after " + input.timeoutMs() + " milliseconds"));
                }
                readerDone.await(1, TimeUnit.SECONDS);
                if (readerError.get() != null) {
                    throw readerError.get();
                }
                context.throwIfCancellationRequested();
                ShellOutputCapture.Snapshot snapshot = output.snapshot(true);
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    return ToolCallResult.failedOutput(result(command, exitCode, startedAt, snapshot,
                            false, "Exit code " + exitCode));
                }
                return ToolCallResult.success(result(command, exitCode, startedAt, snapshot, false, null));
            }
        } catch (ToolCancelledException e) {
            throw e;
        } catch (Exception e) {
            return ToolCallResult.failure(ToolFailure.runtime(e.getMessage()));
        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
            if (stdoutReaderThread != null) {
                stdoutReaderThread.interrupt();
            }
            if (stderrReaderThread != null) {
                stderrReaderThread.interrupt();
            }
        }
    }

    @Override
    public ModelToolResult toModelResult(Output output, ToolResultContext<Input, Output> context) {
        List<String> parts = new ArrayList<>();
        String stdout = output.stdout();
        if (stdout != null && !stdout.isBlank()) {
            parts.add(stdout.replaceFirst("^(\\s*\\n)+", "").stripTrailing());
        }

        StringBuilder errorMessage = new StringBuilder();
        if (output.stderr() != null && !output.stderr().isBlank()) {
            errorMessage.append(output.stderr().trim());
        }
        if (output.status() != null && !output.status().isBlank()) {
            if (!errorMessage.isEmpty()) {
                errorMessage.append('\n');
            }
            errorMessage.append(output.status().trim());
        }
        if (output.interrupted()) {
            if (!errorMessage.isEmpty()) {
                errorMessage.append('\n');
            }
            errorMessage.append("<error>Command was aborted before completion</error>");
        }
        if (!errorMessage.isEmpty()) {
            parts.add(errorMessage.toString());
        }

        return ModelToolResult.text(String.join("\n", parts));
    }

    @Override
    public ToolDisplayResult toDisplayResult(Output output, ToolResultContext<Input, Output> context) {
        return ToolDisplayResult.of("powershell", output.details());
    }

    private void readOutput(
            InputStream input,
            OutputAppender appendOutput,
            ShellOutputCapture output,
            ToolUseContext context,
            String command,
            CountDownLatch readerDone,
            AtomicReference<Exception> readerError
    ) {
        byte[] buffer = new byte[4096];
        long lastUpdateAt = 0L;
        try (input) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                appendOutput.append(buffer, read);
                long now = System.currentTimeMillis();
                if (now - lastUpdateAt >= 100L) {
                    lastUpdateAt = now;
                    emitPartial(output, context, command);
                }
            }
            emitPartial(output, context, command);
        } catch (Exception e) {
            readerError.set(e);
        } finally {
            readerDone.countDown();
        }
    }

    private void emitPartial(ShellOutputCapture output, ToolUseContext context, String command) {
        try {
            ShellOutputCapture.Snapshot snapshot = output.snapshot(false);
            context.emitUpdate(ToolDisplayResult.text("powershell", snapshot.content(), partialDetails(command, snapshot)));
        } catch (IOException ignored) {
        } catch (IllegalArgumentException ignored) {
        }
    }

    private Output result(
            String command,
            Integer exitCode,
            Instant startedAt,
            ShellOutputCapture.Snapshot snapshot,
            boolean interrupted,
            String status
    ) {
        return new Output(
                snapshot.stdout().content(),
                snapshot.stderr().content(),
                interrupted,
                exitCode != null && exitCode != 0 ? status : null,
                null,
                null,
                null,
                status,
                command,
                exitCode,
                Duration.between(startedAt, Instant.now()).toMillis(),
                snapshot.truncationDetails(),
                snapshot.content(),
                snapshot.stdout().truncation().outputLines(),
                snapshot.stderr().truncation().outputLines(),
                snapshot.stdout().truncation().totalLines(),
                snapshot.stderr().truncation().totalLines(),
                snapshot.stdout().truncation().outputBytes(),
                snapshot.stderr().truncation().outputBytes(),
                snapshot.stdout().truncation().totalBytes(),
                snapshot.stderr().truncation().totalBytes(),
                snapshot.stdout().truncated(),
                snapshot.stderr().truncated(),
                snapshot.truncated(),
                snapshot.stdout().fullOutputPath() == null ? null : snapshot.stdout().fullOutputPath().toString(),
                snapshot.stderr().fullOutputPath() == null ? null : snapshot.stderr().fullOutputPath().toString(),
                snapshot.aggregate().fullOutputPath() == null ? null : snapshot.aggregate().fullOutputPath().toString()
        );
    }

    private Map<String, Object> partialDetails(
            String command,
            ShellOutputCapture.Snapshot snapshot
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "powershell");
        details.put("command", command);
        details.put("partial", true);
        details.put("stdout", snapshot.stdout().content());
        details.put("stderr", snapshot.stderr().content());
        details.put("aggregatedOutput", snapshot.content());
        details.put("stdoutLineCount", snapshot.stdout().truncation().outputLines());
        details.put("stderrLineCount", snapshot.stderr().truncation().outputLines());
        details.put("stdoutTotalLines", snapshot.stdout().truncation().totalLines());
        details.put("stderrTotalLines", snapshot.stderr().truncation().totalLines());
        details.put("stdoutByteCount", snapshot.stdout().truncation().outputBytes());
        details.put("stderrByteCount", snapshot.stderr().truncation().outputBytes());
        details.put("stdoutTotalBytes", snapshot.stdout().truncation().totalBytes());
        details.put("stderrTotalBytes", snapshot.stderr().truncation().totalBytes());
        details.put("stdoutTruncated", snapshot.stdout().truncated());
        details.put("stderrTruncated", snapshot.stderr().truncated());
        details.put("truncated", snapshot.truncated());
        details.put("truncation", snapshot.truncationDetails());
        return details;
    }

    private String powerShellEditionSection() {
        Optional<String> command = PowerShell.command();
        if (command.isPresent()) {
            String executable = Path.of(command.get()).getFileName().toString().toLowerCase(Locale.ROOT);
            if (executable.contains("powershell")) {
                return """
                        PowerShell edition: Windows PowerShell 5.1 (powershell.exe)
                           - Pipeline chain operators `&&` and `||` are NOT available - they cause a parser error. To run B only if A succeeds: `A; if ($?) { B }`. To chain unconditionally: `A; B`.
                           - Ternary (`?:`), null-coalescing (`??`), and null-conditional (`?.`) operators are NOT available. Use `if/else` and explicit `$null -eq` checks instead.
                           - Avoid `2>&1` on native executables. In 5.1, redirecting a native command's stderr inside PowerShell wraps each line in an ErrorRecord (NativeCommandError) and sets `$?` to `$false` even when the exe returned exit code 0. stderr is already captured for you - don't redirect it.
                           - Default file encoding is UTF-16 LE (with BOM). When writing files other tools will read, pass `-Encoding utf8` to `Out-File`/`Set-Content`.
                           - `ConvertFrom-Json` returns a PSCustomObject, not a hashtable. `-AsHashtable` is not available.""";
            }
            if (executable.contains("pwsh")) {
                return """
                        PowerShell edition: PowerShell 7+ (pwsh)
                           - Pipeline chain operators `&&` and `||` ARE available and work like bash. Prefer `cmd1 && cmd2` over `cmd1; cmd2` when cmd2 should only run if cmd1 succeeds.
                           - Ternary (`$cond ? $a : $b`), null-coalescing (`??`), and null-conditional (`?.`) operators are available.
                           - Default file encoding is UTF-8 without BOM.""";
            }
        }
        return """
                PowerShell edition: unknown - assume Windows PowerShell 5.1 for compatibility
                   - Do NOT use `&&`, `||`, ternary `?:`, null-coalescing `??`, or null-conditional `?.`. These are PowerShell 7+ only and parser-error on 5.1.
                   - To chain commands conditionally: `A; if ($?) { B }`. Unconditionally: `A; B`.""";
    }

    private static int optionalPositiveNumber(Map<String, Object> arguments, String name, int defaultValue, int maxValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a positive number");
            }
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be a positive number");
        }
        if (parsed > maxValue) {
            throw new IllegalArgumentException(name + " must be <= " + maxValue);
        }
        return parsed;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return stringValue;
    }

    public record Input(String command, int timeoutMs) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "command"),
                    optionalPositiveNumber(arguments, "timeout", DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS)
            );
        }
    }

    public record Output(
            String stdout,
            String stderr,
            boolean interrupted,
            String returnCodeInterpretation,
            Boolean isImage,
            String persistedOutputPath,
            Long persistedOutputSize,
            String status,
            String command,
            Integer exitCode,
            long durationMs,
            Map<String, Object> truncation,
            String aggregatedOutput,
            int stdoutLineCount,
            int stderrLineCount,
            int stdoutTotalLines,
            int stderrTotalLines,
            int stdoutByteCount,
            int stderrByteCount,
            int stdoutTotalBytes,
            int stderrTotalBytes,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean truncated,
            String stdoutFullOutputPath,
            String stderrFullOutputPath,
            String aggregateFullOutputPath
    ) {
        public Output {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
            aggregatedOutput = aggregatedOutput == null ? "" : aggregatedOutput;
            truncation = truncation == null ? Map.of() : new LinkedHashMap<>(truncation);
        }

        Map<String, Object> details() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", "powershell");
            details.put("command", command);
            if (exitCode != null) {
                details.put("exitCode", exitCode);
            }
            if (status != null && !status.isBlank()) {
                details.put("status", status);
            }
            details.put("durationMs", durationMs);
            details.put("truncation", truncation);
            details.put("stdout", stdout);
            details.put("stderr", stderr);
            details.put("aggregatedOutput", aggregatedOutput);
            details.put("stdoutLineCount", stdoutLineCount);
            details.put("stderrLineCount", stderrLineCount);
            details.put("stdoutTotalLines", stdoutTotalLines);
            details.put("stderrTotalLines", stderrTotalLines);
            details.put("stdoutByteCount", stdoutByteCount);
            details.put("stderrByteCount", stderrByteCount);
            details.put("stdoutTotalBytes", stdoutTotalBytes);
            details.put("stderrTotalBytes", stderrTotalBytes);
            details.put("stdoutTruncated", stdoutTruncated);
            details.put("stderrTruncated", stderrTruncated);
            details.put("truncated", truncated);
            if (stdoutFullOutputPath != null) {
                details.put("stdoutFullOutputPath", stdoutFullOutputPath);
            }
            if (stderrFullOutputPath != null) {
                details.put("stderrFullOutputPath", stderrFullOutputPath);
            }
            if (aggregateFullOutputPath != null) {
                details.put("aggregateFullOutputPath", aggregateFullOutputPath);
            }
            return details;
        }
    }

    @FunctionalInterface
    private interface OutputAppender {
        void append(byte[] bytes, int length);
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (Exception ignored) {
            }
        });
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }
}
