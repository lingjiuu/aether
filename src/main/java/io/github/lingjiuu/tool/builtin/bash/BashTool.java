package io.github.lingjiuu.tool.builtin.bash;

import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.builtin.ExecutableFinder;
import io.github.lingjiuu.tool.builtin.shell.ShellOutputCapture;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

public class BashTool implements Tool<BashTool.Input, BashTool.Output> {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MAX_TIMEOUT_MS = 600_000;

    private final Path workspaceRoot;

    public BashTool(WorkspaceAccessPolicy accessPolicy) {
        this(accessPolicy == null ? null : accessPolicy.root());
    }

    public BashTool(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String label() {
        return "Bash";
    }

    @Override
    public String description() {
        return """
                Executes a given bash command and returns its output.
                
                IMPORTANT: Avoid using this tool to run `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. Instead, use the appropriate dedicated tool as this will provide a much better experience for the user:
                
                 - File search: Use Glob (NOT find or ls)
                 - Content search: Use Grep (NOT grep or rg)
                 - Read files: Use Read (NOT cat/head/tail)
                 - Edit files: Use Edit (NOT sed/awk)
                 - Write files: Use Write (NOT echo >/cat <<EOF)
                 - Communication: Output text directly (NOT echo/printf)
                While the Bash tool can do similar things, it’s better to use the built-in tools as they provide a better user experience and make it easier to review tool calls and give permission.
                
                # Instructions
                 - If your command will create new directories or files, first use this tool to run `ls` to verify the parent directory exists and is the correct location.
                 - Always quote file paths that contain spaces with double quotes in your command (e.g., cd "path with spaces/file.txt")
                 - Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it.
                 - You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). By default, your command will timeout after 120000ms (2 minutes).
                 - When issuing multiple commands:
                   - If the commands are independent and can run in parallel, make multiple Bash tool calls in a single message. Example: if you need to run "git status" and "git diff", send a single message with two Bash tool calls in parallel.
                   - If the commands depend on each other and must run sequentially, use a single Bash call with '&&' to chain them together.
                   - Use ';' only when you need to run commands sequentially but don't care if earlier commands fail.
                   - DO NOT use newlines to separate commands (newlines are ok in quoted strings).
                 - For git commands:
                   - Prefer to create a new commit rather than amending an existing commit.
                   - Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), consider whether there is a safer alternative that achieves the same goal. Only use destructive operations when they are truly the best approach.
                   - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.
                 - Avoid unnecessary `sleep` commands:
                   - Do not sleep between commands that can run immediately — just run them.
                   - Do not retry failing commands in a sleep loop — diagnose the root cause.
                   - If you must poll an external process, use a check command (e.g. `gh run view`) rather than sleeping first.
                """;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "The command to execute"),
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
                "properties", Map.ofEntries(
                        Map.entry("stdout", Map.of("type", "string", "description", "The standard output of the command")),
                        Map.entry("stderr", Map.of("type", "string", "description", "The standard error output of the command")),
                        Map.entry("rawOutputPath", Map.of("type", "string", "description", "Path to raw output file for large MCP tool outputs")),
                        Map.entry("interrupted", Map.of("type", "boolean", "description", "Whether the command was interrupted")),
                        Map.entry("isImage", Map.of("type", "boolean", "description", "Flag to indicate if stdout contains image data")),
                        Map.entry("returnCodeInterpretation", Map.of("type", "string", "description", "Semantic interpretation for non-error exit codes with special meaning")),
                        Map.entry("noOutputExpected", Map.of("type", "boolean", "description", "Whether the command is expected to produce no output on success")),
                        Map.entry("structuredContent", Map.of("type", "array", "items", Map.of(), "description", "Structured content blocks")),
                        Map.entry("persistedOutputPath", Map.of("type", "string", "description", "Path to the persisted full output in tool-results dir (set when output is too large for inline)")),
                        Map.entry("persistedOutputSize", Map.of("type", "number", "description", "Total size of the output in bytes (set when output is too large for inline)"))
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
            Optional<List<String>> shellCommand = shellCommand(command);
            if (shellCommand.isEmpty()) {
                return ToolCallResult.failure(ToolFailure.runtime("Git Bash is required on Windows. Install Git for Windows or set AETHER_GIT_BASH_PATH to bash.exe."));
            }
            ShellOutputCapture output = new ShellOutputCapture("aether-bash");
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
                    "aether-bash-stdout"
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
                    "aether-bash-stderr"
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
        return ToolDisplayResult.of("bash", output.details());
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
            context.emitUpdate(ToolDisplayResult.text("bash", snapshot.content(), partialDetails(command, snapshot)));
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
                snapshot.aggregate().fullOutputPath() == null ? null : snapshot.aggregate().fullOutputPath().toString(),
                interrupted,
                null,
                exitCode != null && exitCode != 0 ? status : null,
                null,
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
        details.put("kind", "bash");
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
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
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
            String rawOutputPath,
            boolean interrupted,
            Boolean isImage,
            String returnCodeInterpretation,
            Boolean noOutputExpected,
            List<Object> structuredContent,
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
            details.put("kind", "bash");
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

    public static boolean isAvailable() {
        return shellCommand("true").isPresent();
    }

    static Optional<String> gitBashPath() {
        String explicitPath = System.getenv("AETHER_GIT_BASH_PATH");
        if (explicitPath != null && !explicitPath.isBlank() && isUsableWindowsExecutable(Path.of(explicitPath))) {
            return Optional.of(explicitPath);
        }

        for (String candidate : List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe"
        )) {
            Path path = Path.of(candidate);
            if (isUsableWindowsExecutable(path)) {
                return Optional.of(path.toString());
            }
        }

        Optional<String> git = ExecutableFinder.findOnPath("git.exe");
        if (git.isEmpty()) {
            git = ExecutableFinder.findOnPath("git");
        }
        if (git.isPresent()) {
            Path gitPath = Path.of(git.get());
            Path gitRoot = gitPath.getParent() == null ? null : gitPath.getParent().getParent();
            if (gitRoot != null) {
                Path bash = gitRoot.resolve("bin").resolve("bash.exe");
                if (isUsableWindowsExecutable(bash)) {
                    return Optional.of(bash.toString());
                }
            }
        }

        Optional<String> bash = ExecutableFinder.findOnPath("bash.exe");
        return bash.filter(path -> isUsableWindowsExecutable(Path.of(path)));
    }

    private static Optional<List<String>> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return gitBashPath().map(path -> List.of(path, "-c", command));
        }
        if (Files.isExecutable(Path.of("/bin/bash"))) {
            return Optional.of(List.of("/bin/bash", "-c", command));
        }
        Optional<String> bash = ExecutableFinder.findOnPath("bash");
        if (bash.isPresent()) {
            return Optional.of(List.of(bash.get(), "-c", command));
        }
        return Optional.of(List.of("sh", "-c", command));
    }

    private static boolean isUsableWindowsExecutable(Path path) {
        return path != null && Files.isRegularFile(path);
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
