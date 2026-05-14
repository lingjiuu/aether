package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import io.github.lingjiuu.tool.render.ToolRenderedOutput;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class FindTool implements ToolDefinition {

    private final FileAccessPolicy accessPolicy;
    private final ToolBinaryResolver binaryResolver;

    public FindTool(FileAccessPolicy accessPolicy) {
        this(accessPolicy, ToolBinaryResolver.defaults());
    }

    FindTool(FileAccessPolicy accessPolicy, ToolBinaryResolver binaryResolver) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        if (binaryResolver == null) {
            throw new IllegalArgumentException("binaryResolver must not be null");
        }
        this.accessPolicy = accessPolicy;
        this.binaryResolver = binaryResolver;
    }

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String label() {
        return "find";
    }

    @Override
    public String description() {
        return "Search for files by glob pattern. Returns matching file paths relative to the search directory. "
                + "Respects .gitignore. Output is truncated to " + ToolOutputLimits.FIND_DEFAULT_LIMIT
                + " results or " + ToolOutputTruncator.formatSize(ToolOutputLimits.DEFAULT_MAX_BYTES)
                + " (whichever is hit first).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of(
                                "type", "string",
                                "description", "Glob pattern to match files, e.g. '*.java', '**/*.json', or 'src/**/*.java'."
                        ),
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory to search in (default: current directory)."
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "description", "Maximum number of results (default: 1000)."
                        )
                ),
                "required", List.of("pattern"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolSourceInfo sourceInfo() {
        return ToolSourceInfo.builtin();
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL_SAFE;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public String promptSnippet() {
        return "Find files by glob pattern (respects .gitignore)";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use find to discover candidate files before searching or inspecting them.");
    }

    @Override
    public ToolRenderedOutput renderCall(ToolRenderRequest request) {
        String pattern = stringArg(request, "pattern", "<pattern>");
        String path = stringArg(request, "path", ".");
        Object limit = request.arguments().get("limit");
        String text = "find " + pattern + " in " + path;
        return ToolRenderedOutput.text(limit == null ? text : text + " limit=" + limit);
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
        try {
            context.throwIfCancellationRequested();
            String pattern = BuiltinToolArguments.requiredString(context.getArguments(), "pattern");
            String requestedPath = BuiltinToolArguments.optionalString(context.getArguments(), "path", ".");
            int limit = BuiltinToolArguments.optionalPositiveInt(context.getArguments(), "limit", ToolOutputLimits.FIND_DEFAULT_LIMIT);
            Path resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            Optional<String> fdPath = binaryResolver.resolve(ToolBinary.FD);
            if (fdPath.isEmpty()) {
                return ToolExecutionResult.errorText("find failed: fd is not available and could not be downloaded");
            }
            return findFiles(context, fdPath.get(), pattern, requestedPath, resolvedPath, limit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("find failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult findFiles(ToolExecutionContext context, String fdPath, String pattern, String requestedPath, Path resolvedPath, int limit) throws IOException, InterruptedException {
        context.throwIfCancellationRequested();
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw new IOException("Not a directory: " + requestedPath);
        }

        List<String> args = new ArrayList<>();
        args.add(fdPath);
        args.add("--glob");
        args.add("--color=never");
        args.add("--hidden");
        args.add("--no-require-git");
        args.add("--max-results");
        args.add(String.valueOf(limit));

        String effectivePattern = pattern;
        if (pattern.contains("/")) {
            args.add("--full-path");
            if (!pattern.startsWith("/") && !pattern.startsWith("**/") && !pattern.equals("**")) {
                effectivePattern = "**/" + pattern;
            }
        }
        args.add("--");
        args.add(effectivePattern);
        args.add(resolvedPath.toString());

        Process process = new ProcessBuilder(args)
                .redirectErrorStream(false)
                .start();
        AutoCloseable cancelRegistration = context.cancellationToken().onCancel(process::destroyForcibly);
        try {
            List<String> lines = readLines(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            Duration timeout = context.remainingTimeoutOr(Duration.ofSeconds(30));
            if (timeout.isZero()) {
                process.destroyForcibly();
                throw new IOException("fd timed out");
            }
            boolean finished = process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                context.throwIfCancellationRequested();
                throw new IOException("fd timed out");
            }
            context.throwIfCancellationRequested();
            int exitCode = process.exitValue();
            if (exitCode != 0 && lines.isEmpty()) {
                String message = stderr.isBlank() ? "fd exited with code " + exitCode : stderr;
                throw new IOException(message);
            }

            List<String> returned = new ArrayList<>();
            for (String line : lines) {
                String cleaned = line.replace("\r", "").trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
                returned.add(relativizeOutput(resolvedPath, cleaned));
            }
            boolean resultLimitReached = returned.size() >= limit;
            String rawOutput = returned.isEmpty() ? "No files found matching pattern" : String.join("\n", returned);
            ToolOutputTruncator.TruncationResult truncation = ToolOutputTruncator.truncateHead(rawOutput, ToolOutputLimits.DEFAULT_MAX_BYTES);
            String output = truncation.content();
            List<String> notices = new ArrayList<>();
            if (resultLimitReached) {
                notices.add(limit + " results limit reached. Use limit=" + (limit * 2) + " for more, or refine pattern");
            }
            if (truncation.truncated()) {
                notices.add(ToolOutputTruncator.formatSize(ToolOutputLimits.DEFAULT_MAX_BYTES) + " limit reached");
            }
            if (!notices.isEmpty()) {
                output += "\n\n[" + String.join(". ", notices) + "]";
            }

            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(output).getContents())
                    .details(Map.of(
                            "pattern", pattern,
                            "path", requestedPath,
                            "resolvedPath", resolvedPath.toString(),
                            "returnedResults", returned.size(),
                            "resultLimitReached", resultLimitReached,
                            "truncated", truncation.truncated()
                    ))
                    .error(false)
                    .build();
        } finally {
            closeQuietly(cancelRegistration);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> readLines(java.io.InputStream inputStream) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String readAll(java.io.InputStream inputStream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        return output.toString();
    }

    private String relativizeOutput(Path searchPath, String outputLine) {
        boolean trailingSlash = outputLine.endsWith("/") || outputLine.endsWith("\\");
        Path outputPath = Path.of(outputLine);
        String relative;
        if (outputPath.isAbsolute()) {
            Path normalized = outputPath.toAbsolutePath().normalize();
            if (normalized.startsWith(searchPath)) {
                relative = toPosix(searchPath.relativize(normalized));
            } else {
                relative = toPosix(searchPath.relativize(normalized));
            }
        } else {
            relative = outputLine;
            while (relative.startsWith("./") || relative.startsWith(".\\")) {
                relative = relative.substring(2);
            }
        }
        relative = relative.replace('\\', '/');
        if (trailingSlash && !relative.endsWith("/")) {
            relative += "/";
        }
        return relative;
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String stringArg(ToolRenderRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : defaultValue;
    }
}
