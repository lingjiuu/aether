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
import io.github.lingjiuu.tool.result.ToolResultLimits;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PowerShellTool implements Tool<PowerShellTool.Input, PowerShellTool.Output> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

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
        return "powershell";
    }

    @Override
    public String label() {
        return "powershell";
    }

    @Override
    public String description() {
        return "Execute a PowerShell command in the workspace. Returns stdout and stderr. Output is truncated to the last "
                + ShellOutputCapture.DEFAULT_MAX_LINES + " lines or "
                + ToolResultLimits.formatSize(ShellOutputCapture.DEFAULT_MAX_BYTES)
                + ", whichever is hit first during execution. Large final output is saved as a tool result artifact. "
                + "Optionally provide timeout in seconds.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "PowerShell command to execute."),
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
                return ToolCallResult.failure(ToolFailure.runtime("PowerShell is not available on Windows."));
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
                boolean finished = process.waitFor(input.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    destroyProcessTree(process);
                    readerDone.await(1, TimeUnit.SECONDS);
                    ShellOutputCapture.Snapshot snapshot = output.snapshot(true);
                    return ToolCallResult.failedOutput(result(command, null, startedAt, snapshot,
                            "Command timed out after " + input.timeoutSeconds() + " seconds"));
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
                            "Command exited with code " + exitCode));
                }
                return ToolCallResult.success(result(command, exitCode, startedAt, snapshot, null));
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
        return ModelToolResult.text(output.text());
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
            String status
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
        details.put("kind", "powershell");
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
        return new Output(text, details);
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

    public record Input(String command, int timeoutSeconds) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "command"),
                    optionalPositiveNumber(arguments, "timeout", DEFAULT_TIMEOUT_SECONDS)
            );
        }
    }

    public record Output(String text, Map<String, Object> details) {
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
