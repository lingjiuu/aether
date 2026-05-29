package io.github.lingjiuu.tool.builtin.bash;

import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.builtin.ExecutableFinder;
import io.github.lingjiuu.tool.builtin.shell.ShellOutputCapture;
import io.github.lingjiuu.tool.result.ToolResultLimits;
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

public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

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
        return "bash";
    }

    @Override
    public String label() {
        return "bash";
    }

    @Override
    public String description() {
        return """
                Executes a given bash command and returns its output.
                
                The working directory persists between commands, but shell state does not. The shell environment is initialized from the user's profile (bash or zsh).
                
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
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "The command to execute."),
                        "timeout", Map.of("type", "number", "minimum", 1, "description", "Optional timeout in milliseconds (max 600000).")
                ),
                "required", List.of("command"),
                "additionalProperties", false
        );
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
    public ToolExecutionResult execute(ToolInvocation context) {
        Instant startedAt = Instant.now();
        Process process = null;
        Thread stdoutReaderThread = null;
        Thread stderrReaderThread = null;
        try {
            context.throwIfCancellationRequested();
            Args args = Args.from(context.getArguments());
            String command = args.command();
            Optional<List<String>> shellCommand = shellCommand(command);
            if (shellCommand.isEmpty()) {
                return ToolExecutionResult.errorText("bash failed: Git Bash is required on Windows. Install Git for Windows or set AETHER_GIT_BASH_PATH to bash.exe.");
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
                boolean finished = process.waitFor(args.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    destroyProcessTree(process);
                    readerDone.await(1, TimeUnit.SECONDS);
                    ShellOutputCapture.Snapshot snapshot = output.snapshot(true);
                    return result(command, null, startedAt, snapshot,
                            "Command timed out after " + args.timeoutSeconds() + " seconds", true);
                }
                readerDone.await(1, TimeUnit.SECONDS);
                if (readerError.get() != null) {
                    throw readerError.get();
                }
                context.throwIfCancellationRequested();
                ShellOutputCapture.Snapshot snapshot = output.snapshot(true);
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    return result(command, exitCode, startedAt, snapshot,
                            "Command exited with code " + exitCode, true);
                }
                return result(command, exitCode, startedAt, snapshot, null, false);
            }
        } catch (ToolCancelledException e) {
            throw e;
        } catch (Exception e) {
            return ToolExecutionResult.errorText("bash failed: " + e.getMessage());
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

    private void readOutput(
            InputStream input,
            OutputAppender appendOutput,
            ShellOutputCapture output,
            ToolInvocation context,
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

    private void emitPartial(ShellOutputCapture output, ToolInvocation context, String command) {
        try {
            ShellOutputCapture.Snapshot snapshot = output.snapshot(false);
            context.emitUpdate(ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(snapshot.content()).getContents())
                    .details(partialDetails(command, snapshot))
                    .error(false)
                    .build());
        } catch (IOException ignored) {
        } catch (IllegalArgumentException ignored) {
        }
    }

    private ToolExecutionResult result(
            String command,
            Integer exitCode,
            Instant startedAt,
            ShellOutputCapture.Snapshot snapshot,
            String status,
            boolean error
    ) {
        String text = snapshot.content() == null || snapshot.content().isBlank() ? "(no output)" : snapshot.content();
        String notice = truncationNotice(snapshot);
        if (!notice.isBlank()) {
            text += "\n\n" + notice;
        }
        if (status != null && !status.isBlank()) {
            text += "\n\n" + status;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "bash");
        details.put("command", command);
        if (exitCode != null) {
            details.put("exitCode", exitCode);
        }
        details.put("durationMs", Duration.between(startedAt, Instant.now()).toMillis());
        details.put("truncation", snapshot.truncationDetails());
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
        if (snapshot.stdout().fullOutputPath() != null) {
            details.put("stdoutFullOutputPath", snapshot.stdout().fullOutputPath().toString());
        }
        if (snapshot.stderr().fullOutputPath() != null) {
            details.put("stderrFullOutputPath", snapshot.stderr().fullOutputPath().toString());
        }
        if (snapshot.aggregate().fullOutputPath() != null) {
            details.put("aggregateFullOutputPath", snapshot.aggregate().fullOutputPath().toString());
        }
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(text).getContents())
                .details(details)
                .error(error)
                .build();
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

    private String truncationNotice(ShellOutputCapture.Snapshot snapshot) {
        List<String> notices = new ArrayList<>();
        addTruncationNotice(notices, "stdout", snapshot.stdout());
        addTruncationNotice(notices, "stderr", snapshot.stderr());
        return String.join("\n", notices);
    }

    private void addTruncationNotice(
            List<String> notices,
            String streamName,
            ShellOutputCapture.StreamSnapshot stream
    ) {
        if (!stream.truncated() || stream.fullOutputPath() == null) {
            return;
        }
        ShellOutputCapture.StreamTruncation truncation = stream.truncation();
        if (truncation.lastLinePartial()) {
            int line = truncation.totalLines();
            notices.add("[Showing last " + ToolResultLimits.formatSize(truncation.outputBytes())
                    + " of " + streamName + " line " + line
                    + ". Full output: " + stream.fullOutputPath() + "]");
            return;
        }
        int startLine = Math.max(1, truncation.totalLines() - truncation.outputLines() + 1);
        notices.add("[Showing " + streamName + " lines " + startLine + "-" + truncation.totalLines()
                + " of " + truncation.totalLines()
                + ". Full output: " + stream.fullOutputPath() + "]");
    }

    private static int optionalPositiveNumber(Map<String, Object> arguments, String name, int defaultValue) {
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
        return parsed;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return stringValue;
    }

    private record Args(String command, int timeoutSeconds) {
        static Args from(Map<String, Object> arguments) {
            return new Args(
                    requiredString(arguments, "command"),
                    optionalPositiveNumber(arguments, "timeout", DEFAULT_TIMEOUT_SECONDS)
            );
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
