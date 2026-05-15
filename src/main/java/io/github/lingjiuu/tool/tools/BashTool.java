package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import io.github.lingjiuu.tool.render.ToolRenderedOutput;

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
    public ToolSourceInfo sourceInfo() {
        return ToolSourceInfo.builtin();
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.DESTRUCTIVE;
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
    public ToolRenderedOutput renderCall(ToolRenderRequest request) {
        String command = stringArg(request, "command", "<command>");
        Object timeout = request.arguments().get("timeout");
        String suffix = timeout == null ? "" : " (timeout " + timeout + "s)";
        return ToolRenderedOutput.text("$ " + command + suffix);
    }

    @Override
    public ToolRenderedOutput renderResult(ToolRenderRequest request) {
        if (request.toolResult() == null) {
            return null;
        }
        return ToolRenderedOutput.text(MessageContents.text(request.toolResult()));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        Instant startedAt = Instant.now();
        Process process = null;
        Thread readerThread = null;
        try {
            context.throwIfCancellationRequested();
            String command = ToolArguments.requiredString(context.getArguments(), "command");
            int timeoutSeconds = optionalPositiveNumber(context.getArguments(), "timeout", DEFAULT_TIMEOUT_SECONDS);
            List<String> shellCommand = shellCommand(command);
            BashOutputAccumulator output = new BashOutputAccumulator();
            ProcessBuilder builder = new ProcessBuilder(shellCommand)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);
            process = builder.start();
            Process runningProcess = process;
            CountDownLatch readerDone = new CountDownLatch(1);
            AtomicReference<IOException> readerError = new AtomicReference<>();
            readerThread = new Thread(
                    () -> readOutput(runningProcess, output, onUpdate, readerDone, readerError),
                    "aether-bash-output"
            );
            readerThread.setDaemon(true);
            readerThread.start();

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
            if (readerThread != null) {
                readerThread.interrupt();
            }
        }
    }

    private void readOutput(
            Process process,
            BashOutputAccumulator output,
            ToolUpdateCallback onUpdate,
            CountDownLatch readerDone,
            AtomicReference<IOException> readerError
    ) {
        byte[] buffer = new byte[4096];
        long lastUpdateAt = 0L;
        try (InputStream input = process.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.append(buffer, read);
                if (onUpdate != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdateAt >= 100L) {
                        lastUpdateAt = now;
                        emitPartial(output, onUpdate);
                    }
                }
            }
            if (onUpdate != null) {
                emitPartial(output, onUpdate);
            }
        } catch (IOException e) {
            readerError.set(e);
        } finally {
            readerDone.countDown();
        }
    }

    private void emitPartial(BashOutputAccumulator output, ToolUpdateCallback onUpdate) {
        try {
            BashOutputAccumulator.Snapshot snapshot = output.snapshot(false);
            onUpdate.onUpdate(ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(snapshot.content()).getContents())
                    .details(Map.of("partial", true))
                    .error(false)
                    .build());
        } catch (IOException ignored) {
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
        details.put("command", command);
        if (exitCode != null) {
            details.put("exitCode", exitCode);
        }
        details.put("durationMillis", Duration.between(startedAt, Instant.now()).toMillis());
        details.put("truncation", snapshot.truncationDetails());
        if (snapshot.fullOutputPath() != null) {
            details.put("fullOutputPath", snapshot.fullOutputPath().toString());
        }
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(text).getContents())
                .details(details)
                .error(error)
                .build();
    }

    private String truncationNotice(BashOutputAccumulator.Snapshot snapshot) {
        if (!snapshot.truncated() || snapshot.fullOutputPath() == null) {
            return "";
        }
        TextOutputTruncator.TruncationResult truncation = snapshot.truncation();
        if (truncation.lastLinePartial()) {
            int line = truncation.totalLines();
            return "[Showing last " + TextOutputTruncator.formatSize(truncation.outputBytes())
                    + " of line " + line + ". Full output: " + snapshot.fullOutputPath() + "]";
        }
        int startLine = Math.max(1, truncation.totalLines() - truncation.outputLines() + 1);
        return "[Showing lines " + startLine + "-" + truncation.totalLines()
                + " of " + truncation.totalLines()
                + ". Full output: " + snapshot.fullOutputPath() + "]";
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

    private String stringArg(ToolRenderRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : defaultValue;
    }
}
