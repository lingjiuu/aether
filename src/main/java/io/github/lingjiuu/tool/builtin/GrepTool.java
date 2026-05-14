package io.github.lingjiuu.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class GrepTool implements ToolDefinition {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final FileAccessPolicy accessPolicy;
    private final ToolBinaryResolver binaryResolver;

    public GrepTool(FileAccessPolicy accessPolicy) {
        this(accessPolicy, ToolBinaryResolver.defaults());
    }

    GrepTool(FileAccessPolicy accessPolicy, ToolBinaryResolver binaryResolver) {
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
        return "grep";
    }

    @Override
    public String label() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search file contents for a pattern. Returns matching lines with file paths and line numbers. "
                + "Respects .gitignore. Output is truncated to " + ToolOutputLimits.GREP_DEFAULT_LIMIT
                + " matches or " + ToolOutputTruncator.formatSize(ToolOutputLimits.DEFAULT_MAX_BYTES)
                + " (whichever is hit first). Long lines are truncated to "
                + ToolOutputLimits.GREP_MAX_LINE_LENGTH + " chars. Use read with offset/limit to inspect full lines.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "description", "Search pattern (regex or literal string)."),
                        "path", Map.of("type", "string", "description", "Directory or file to search (default: current directory)."),
                        "glob", Map.of("type", "string", "description", "Filter files by glob pattern, e.g. '*.java' or '**/*.spec.java'."),
                        "ignoreCase", Map.of("type", "boolean", "description", "Case-insensitive search (default: false)."),
                        "literal", Map.of("type", "boolean", "description", "Treat pattern as literal string instead of regex (default: false)."),
                        "context", Map.of("type", "integer", "minimum", 0, "description", "Number of lines to show before and after each match (default: 0)."),
                        "limit", Map.of("type", "integer", "minimum", 1, "description", "Maximum number of matches to return (default: 100).")
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
        return "Search file contents for patterns (respects .gitignore)";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use grep to search file contents before opening broad areas of the project.");
    }

    @Override
    public ToolRenderedOutput renderCall(ToolRenderRequest request) {
        String pattern = stringArg(request, "pattern", "<pattern>");
        String path = stringArg(request, "path", ".");
        String glob = stringArg(request, "glob", null);
        String text = "grep " + pattern + " in " + path;
        return ToolRenderedOutput.text(glob == null ? text : text + " glob=" + glob);
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
            String glob = BuiltinToolArguments.optionalString(context.getArguments(), "glob", null);
            boolean ignoreCase = BuiltinToolArguments.optionalBoolean(context.getArguments(), "ignoreCase", false);
            boolean literal = BuiltinToolArguments.optionalBoolean(context.getArguments(), "literal", false);
            int contextLines = BuiltinToolArguments.optionalNonNegativeInt(context.getArguments(), "context", 0);
            int limit = BuiltinToolArguments.optionalPositiveInt(context.getArguments(), "limit", ToolOutputLimits.GREP_DEFAULT_LIMIT);
            Path resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            Optional<String> rgPath = binaryResolver.resolve(ToolBinary.RG);
            if (rgPath.isEmpty()) {
                return ToolExecutionResult.errorText("grep failed: ripgrep (rg) is not available and could not be downloaded");
            }
            return grep(context, rgPath.get(), pattern, requestedPath, resolvedPath, glob, ignoreCase, literal, contextLines, limit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("grep failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult grep(
            ToolExecutionContext context,
            String rgPath,
            String pattern,
            String requestedPath,
            Path resolvedPath,
            String glob,
            boolean ignoreCase,
            boolean literal,
            int contextLines,
            int limit
    ) throws IOException, InterruptedException {
        context.throwIfCancellationRequested();
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }

        List<String> args = new ArrayList<>();
        args.add(rgPath);
        args.add("--json");
        args.add("--line-number");
        args.add("--color=never");
        args.add("--hidden");
        if (ignoreCase) {
            args.add("--ignore-case");
        }
        if (literal) {
            args.add("--fixed-strings");
        }
        if (glob != null && !glob.isBlank()) {
            args.add("--glob");
            args.add(glob);
        }
        args.add("--");
        args.add(pattern);
        args.add(resolvedPath.toString());

        Process process = new ProcessBuilder(args)
                .redirectErrorStream(false)
                .start();
        AutoCloseable cancelRegistration = context.cancellationToken().onCancel(process::destroyForcibly);
        List<Match> matches = new ArrayList<>();
        boolean matchLimitReached = false;
        try {
            try (BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (line.isBlank() || matches.size() >= limit) {
                        continue;
                    }
                    JsonNode event;
                    try {
                        event = OBJECT_MAPPER.readTree(line);
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (!"match".equals(event.path("type").asText())) {
                        continue;
                    }
                    JsonNode data = event.path("data");
                    String filePath = data.path("path").path("text").asText(null);
                    int lineNumber = data.path("line_number").asInt(-1);
                    String lineText = data.path("lines").path("text").asText("");
                    if (filePath != null && lineNumber > 0) {
                        matches.add(new Match(resolveMatchPath(resolvedPath, filePath), lineNumber, lineText));
                        if (matches.size() >= limit) {
                            matchLimitReached = true;
                            process.destroy();
                            break;
                        }
                    }
                }
            }

            String stderr = matchLimitReached ? "" : readAll(process.getErrorStream());
            Duration timeout = context.remainingTimeoutOr(Duration.ofSeconds(30));
            if (timeout.isZero()) {
                process.destroyForcibly();
                throw new IOException("ripgrep timed out");
            }
            boolean finished = process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                context.throwIfCancellationRequested();
                throw new IOException("ripgrep timed out");
            }
            context.throwIfCancellationRequested();
            int exitCode = process.exitValue();
            if (!matchLimitReached && exitCode != 0 && exitCode != 1) {
                String message = stderr.isBlank() ? "ripgrep exited with code " + exitCode : stderr;
                throw new IOException(message);
            }
            return render(pattern, requestedPath, resolvedPath, glob, matches, matchLimitReached, contextLines);
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

    private ToolExecutionResult render(
            String pattern,
            String requestedPath,
            Path searchPath,
            String glob,
            List<Match> matches,
            boolean matchLimitReached,
            int contextLines
    ) {
        if (matches.isEmpty()) {
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text("No matches found").getContents())
                    .details(Map.of(
                            "pattern", pattern,
                            "path", requestedPath,
                            "resolvedPath", searchPath.toString(),
                            "returnedMatches", 0,
                            "matchLimitReached", false,
                            "truncated", false
                    ))
                    .error(false)
                    .build();
        }

        List<String> outputLines = new ArrayList<>();
        boolean linesTruncated = false;
        for (Match match : matches) {
            if (contextLines == 0) {
                ToolOutputTruncator.LineTruncation line = ToolOutputTruncator.truncateLine(
                        sanitizeMatchLine(match.lineText()),
                        ToolOutputLimits.GREP_MAX_LINE_LENGTH
                );
                if (line.wasTruncated()) {
                    linesTruncated = true;
                }
                outputLines.add(formatPath(searchPath, match.file()) + ":" + match.lineNumber() + ": " + line.text());
                continue;
            }

            List<String> fileLines = readFileLines(match.file());
            if (fileLines.isEmpty()) {
                outputLines.add(formatPath(searchPath, match.file()) + ":" + match.lineNumber() + ": (unable to read file)");
                continue;
            }
            int start = Math.max(1, match.lineNumber() - contextLines);
            int end = Math.min(fileLines.size(), match.lineNumber() + contextLines);
            for (int lineNumber = start; lineNumber <= end; lineNumber++) {
                ToolOutputTruncator.LineTruncation line = ToolOutputTruncator.truncateLine(
                        fileLines.get(lineNumber - 1).replace("\r", ""),
                        ToolOutputLimits.GREP_MAX_LINE_LENGTH
                );
                if (line.wasTruncated()) {
                    linesTruncated = true;
                }
                String separator = lineNumber == match.lineNumber() ? ":" : "-";
                outputLines.add(formatPath(searchPath, match.file()) + separator + lineNumber + separator + " " + line.text());
            }
        }

        String rawOutput = String.join("\n", outputLines);
        ToolOutputTruncator.TruncationResult truncation = ToolOutputTruncator.truncateHead(rawOutput, ToolOutputLimits.DEFAULT_MAX_BYTES);
        String output = truncation.content();
        List<String> notices = new ArrayList<>();
        if (matchLimitReached) {
            notices.add(matches.size() + " matches limit reached. Use limit=" + (matches.size() * 2) + " for more, or refine pattern");
        }
        if (truncation.truncated()) {
            notices.add(ToolOutputTruncator.formatSize(ToolOutputLimits.DEFAULT_MAX_BYTES) + " limit reached");
        }
        if (linesTruncated) {
            notices.add("Some lines truncated to " + ToolOutputLimits.GREP_MAX_LINE_LENGTH
                    + " chars. Use read with offset/limit to inspect full lines");
        }
        if (!notices.isEmpty()) {
            output += "\n\n[" + String.join(". ", notices) + "]";
        }

        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(output).getContents())
                .details(Map.of(
                        "pattern", pattern,
                        "path", requestedPath,
                        "glob", glob == null ? "" : glob,
                        "resolvedPath", searchPath.toString(),
                        "returnedMatches", matches.size(),
                        "matchLimitReached", matchLimitReached,
                        "truncated", truncation.truncated(),
                        "linesTruncated", linesTruncated
                ))
                .error(false)
                .build();
    }

    private String formatPath(Path searchPath, Path file) {
        if (Files.isDirectory(searchPath)) {
            Path normalizedFile = file.toAbsolutePath().normalize();
            Path normalizedSearch = searchPath.toAbsolutePath().normalize();
            if (normalizedFile.startsWith(normalizedSearch)) {
                return toPosix(normalizedSearch.relativize(normalizedFile));
            }
        }
        return file.getFileName().toString();
    }

    private Path resolveMatchPath(Path searchPath, String filePath) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        String cleaned = filePath;
        while (cleaned.startsWith("./") || cleaned.startsWith(".\\")) {
            cleaned = cleaned.substring(2);
        }
        Path base = Files.isDirectory(searchPath) ? searchPath : searchPath.getParent();
        if (base == null) {
            base = searchPath;
        }
        return base.resolve(cleaned).normalize();
    }

    private String sanitizeMatchLine(String lineText) {
        return (lineText == null ? "" : lineText)
                .replace("\r\n", "\n")
                .replace("\r", "")
                .replaceAll("\n$", "");
    }

    private List<String> readFileLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
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

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String stringArg(ToolRenderRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : defaultValue;
    }

    private record Match(Path file, int lineNumber, String lineText) {
    }
}
