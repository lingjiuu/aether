package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BashTool implements ToolDefinition {

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
        return "Execute a bash command in the workspace. Returns stdout and stderr. Output is truncated to the last "
                + ToolOutputLimits.BASH_MAX_LINES + " lines or "
                + TextOutputTruncator.formatSize(ToolOutputLimits.DEFAULT_MAX_BYTES)
                + ", whichever is hit first. If truncated, full output is saved to a temp file. "
                + "Optionally provide timeout in seconds.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "Bash command to execute."),
                        "timeout", Map.of("type", "number", "minimum", 1, "description", "Timeout in seconds.")
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
    public String promptSnippet() {
        return "Execute bash commands";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of(
                "Use bash to run tests, builds, formatters, and project commands.",
                "Prefer grep, find, and ls for file exploration when those tools are available."
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context) {
        Instant startedAt = Instant.now();
        Process process = null;
        Thread stdoutReaderThread = null;
        Thread stderrReaderThread = null;
        try {
            context.throwIfCancellationRequested();
            String command = ToolArguments.requiredString(context.getArguments(), "command");
            int timeoutSeconds = optionalPositiveNumber(context.getArguments(), "timeout", DEFAULT_TIMEOUT_SECONDS);
            List<String> shellCommand = shellCommand(command);
            BashOutputAccumulator output = new BashOutputAccumulator();
            ProcessBuilder builder = new ProcessBuilder(shellCommand)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(false);
            process = builder.start();
            Process runningProcess = process;
            CountDownLatch readerDone = new CountDownLatch(2);
            AtomicReference<IOException> readerError = new AtomicReference<>();
            stdoutReaderThread = new Thread(
                    () -> readOutput(
                            runningProcess.getInputStream(),
                            output::appendStdout,
                            output,
                            context,
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
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    destroyProcessTree(process);
                    readerDone.await(1, TimeUnit.SECONDS);
                    BashOutputAccumulator.Snapshot snapshot = output.snapshot(true);
                    return result(command, null, startedAt, snapshot,
                            "Command timed out after " + timeoutSeconds + " seconds", true);
                }
                readerDone.await(1, TimeUnit.SECONDS);
                if (readerError.get() != null) {
                    throw readerError.get();
                }
                context.throwIfCancellationRequested();
                BashOutputAccumulator.Snapshot snapshot = output.snapshot(true);
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
            BashOutputAccumulator output,
            ToolExecutionContext context,
            CountDownLatch readerDone,
            AtomicReference<IOException> readerError
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
                    emitPartial(output, context);
                }
            }
            emitPartial(output, context);
        } catch (IOException e) {
            readerError.set(e);
        } finally {
            readerDone.countDown();
        }
    }

    private void emitPartial(BashOutputAccumulator output, ToolExecutionContext context) {
        try {
            BashOutputAccumulator.Snapshot snapshot = output.snapshot(false);
            String command = ToolArguments.requiredString(context.getArguments(), "command");
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
            BashOutputAccumulator.Snapshot snapshot,
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
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(text).getContents())
                .details(details)
                .error(error)
                .build();
    }

    private Map<String, Object> partialDetails(
            String command,
            BashOutputAccumulator.Snapshot snapshot
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

    private String truncationNotice(BashOutputAccumulator.Snapshot snapshot) {
        List<String> notices = new ArrayList<>();
        addTruncationNotice(notices, "stdout", snapshot.stdout());
        addTruncationNotice(notices, "stderr", snapshot.stderr());
        return String.join("\n", notices);
    }

    private void addTruncationNotice(
            List<String> notices,
            String streamName,
            BashOutputAccumulator.StreamSnapshot stream
    ) {
        if (!stream.truncated() || stream.fullOutputPath() == null) {
            return;
        }
        TextOutputTruncator.TruncationResult truncation = stream.truncation();
        if (truncation.lastLinePartial()) {
            int line = truncation.totalLines();
            notices.add("[Showing last " + TextOutputTruncator.formatSize(truncation.outputBytes())
                    + " of " + streamName + " line " + line
                    + ". Full output: " + stream.fullOutputPath() + "]");
            return;
        }
        int startLine = Math.max(1, truncation.totalLines() - truncation.outputLines() + 1);
        notices.add("[Showing " + streamName + " lines " + startLine + "-" + truncation.totalLines()
                + " of " + truncation.totalLines()
                + ". Full output: " + stream.fullOutputPath() + "]");
    }

    private int optionalPositiveNumber(Map<String, Object> arguments, String name, int defaultValue) {
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

    private List<String> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            Optional<String> bash = findOnPath("bash.exe");
            if (bash.isPresent()) {
                return List.of(bash.get(), "-c", command);
            }
            return List.of("cmd.exe", "/c", command);
        }
        if (Files.isExecutable(Path.of("/bin/bash"))) {
            return List.of("/bin/bash", "-c", command);
        }
        Optional<String> bash = findOnPath("bash");
        if (bash.isPresent()) {
            return List.of(bash.get(), "-c", command);
        }
        return List.of("sh", "-c", command);
    }

    @FunctionalInterface
    private interface OutputAppender {
        void append(byte[] bytes, int length);
    }

    private Optional<String> findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String separator = System.getProperty("path.separator");
        for (String entry : path.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(executable);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate.toString());
            }
        }
        return Optional.empty();
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
