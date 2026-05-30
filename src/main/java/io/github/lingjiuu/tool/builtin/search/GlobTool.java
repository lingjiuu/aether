package io.github.lingjiuu.tool.builtin.search;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GlobTool implements Tool<GlobTool.Input, GlobTool.Output> {

    private static final int DEFAULT_RESULT_LIMIT = 100;

    private final WorkspaceAccessPolicy accessPolicy;

    public GlobTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String label() {
        return "Glob";
    }

    @Override
    public String description() {
        return """
                - Fast file pattern matching tool that works with any codebase size
                - Supports glob patterns like "**/*.js" or "src/**/*.ts"
                - Returns matching file paths sorted by modification time
                - Use this tool when you need to find files by name patterns
                """;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of(
                                "type", "string",
                                "description", "The glob pattern to match files against."
                        ),
                        "path", Map.of(
                                "type", "string",
                                "description", "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided."
                        )
                ),
                "required", List.of("pattern"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.withMaxResultSizeChars(100_000);
    }

    @Override
    public Input parseInput(String argumentsJson) {
        return Input.from(validateInputJson(argumentsJson));
    }

    @Override
    public ToolCallResult<Output> call(Input input, ToolUseContext context) {
        try {
            context.throwIfCancellationRequested();
            Path resolvedPath = accessPolicy.resolveReadablePath(input.path());
            var rgPath = Ripgrep.command();
            if (rgPath.isEmpty()) {
                return ToolCallResult.failure(ToolFailure.runtime("ripgrep (rg) is unavailable"));
            }
            SearchTarget searchTarget = SearchTarget.from(input.pattern(), resolvedPath, accessPolicy);
            return ToolCallResult.success(glob(context, rgPath.get(), input.pattern(), input.path(), searchTarget));
        } catch (Exception e) {
            return ToolCallResult.failure(ToolFailure.runtime(e.getMessage()));
        }
    }

    @Override
    public ModelToolResult toModelResult(Output output, ToolResultContext<Input, Output> context) {
        return ModelToolResult.text(output.text());
    }

    @Override
    public ToolDisplayResult toDisplayResult(Output output, ToolResultContext<Input, Output> context) {
        return ToolDisplayResult.of("glob", output.details());
    }

    private Output glob(
            ToolUseContext context,
            String rgPath,
            String pattern,
            String requestedPath,
            SearchTarget searchTarget
    ) throws IOException, InterruptedException {
        context.throwIfCancellationRequested();
        Path resolvedPath = searchTarget.directory();
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw new IOException("Not a directory: " + requestedPath);
        }

        List<String> args = new ArrayList<>();
        args.add(rgPath);
        args.add("--files");
        args.add("--glob");
        args.add(searchTarget.pattern());
        args.add("--sort=modified");
        args.add("--no-ignore");
        args.add("--hidden");
        for (String ignoredVcsDir : List.of("!.git/**", "!.svn/**", "!.hg/**", "!.bzr/**", "!.jj/**", "!.sl/**")) {
            args.add("--glob");
            args.add(ignoredVcsDir);
        }
        args.add(resolvedPath.toString());

        Process process = new ProcessBuilder(args)
                .redirectErrorStream(false)
                .directory(accessPolicy.root().toFile())
                .start();
        AutoCloseable cancelRegistration = context.cancellationToken().onCancel(process::destroyForcibly);
        try {
            List<String> lines = readLines(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
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
            if (exitCode != 0 && exitCode != 1) {
                String message = stderr.isBlank() ? "ripgrep exited with code " + exitCode : stderr;
                throw new IOException(message);
            }

            List<String> filenames = new ArrayList<>();
            for (String line : lines) {
                String cleaned = line.replace("\r", "").trim();
                if (!cleaned.isEmpty()) {
                    filenames.add(displayPath(cleaned));
                }
            }

            boolean resultLimitReached = filenames.size() > DEFAULT_RESULT_LIMIT;
            List<String> returned = filenames.size() <= DEFAULT_RESULT_LIMIT
                    ? filenames
                    : filenames.subList(0, DEFAULT_RESULT_LIMIT);
            String rawOutput = returned.isEmpty() ? "No files found" : String.join("\n", returned);
            String output = rawOutput;
            List<String> notices = new ArrayList<>();
            if (resultLimitReached) {
                notices.add("Results are truncated. Consider using a more specific path or pattern");
            }
            if (!notices.isEmpty()) {
                output += "\n\n[" + String.join(". ", notices) + "]";
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", "glob");
            details.put("pattern", pattern);
            details.put("path", requestedPath);
            details.put("resolvedPath", resolvedPath.toString());
            details.put("numFiles", returned.size());
            details.put("filenames", List.copyOf(returned));
            details.put("truncated", resultLimitReached);

            return new Output(output, details);
        } finally {
            closeQuietly(cancelRegistration);
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

    private String displayPath(String outputLine) {
        Path path = Path.of(outputLine);
        Path absolutePath = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : accessPolicy.root().resolve(path).toAbsolutePath().normalize();
        if (absolutePath.startsWith(accessPolicy.root())) {
            return toPosix(accessPolicy.root().relativize(absolutePath));
        }
        return toPosix(absolutePath);
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static SearchTarget absolutePatternTarget(String pattern, WorkspaceAccessPolicy accessPolicy) {
        int globIndex = firstGlobIndex(pattern);
        if (globIndex < 0) {
            Path literalPath = accessPolicy.resolveReadablePath(pattern);
            Path parent = literalPath.getParent();
            if (parent == null) {
                return new SearchTarget(pattern, literalPath);
            }
            return new SearchTarget(literalPath.getFileName().toString(), parent);
        }

        int slashIndex = Math.max(pattern.lastIndexOf('/', globIndex), pattern.lastIndexOf('\\', globIndex));
        if (slashIndex < 0) {
            return new SearchTarget(pattern, accessPolicy.root());
        }

        String base = pattern.substring(0, slashIndex);
        String relativePattern = pattern.substring(slashIndex + 1);
        if (base.isBlank()) {
            base = pattern.startsWith("/") ? "/" : base;
        }
        Path directory = accessPolicy.resolveReadablePath(base);
        return new SearchTarget(relativePattern, directory);
    }

    private static int firstGlobIndex(String pattern) {
        int first = -1;
        for (char globChar : List.of('*', '?', '[', '{')) {
            int index = pattern.indexOf(globChar);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return stringValue;
    }

    private static String optionalString(Map<String, Object> arguments, String name, String defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return stringValue.isBlank() ? defaultValue : stringValue;
    }

    public record Input(String pattern, String path) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "pattern"),
                    optionalString(arguments, "path", ".")
            );
        }
    }

    public record Output(String text, Map<String, Object> details) {
    }

    private record SearchTarget(String pattern, Path directory) {
        static SearchTarget from(
                String pattern,
                Path resolvedPath,
                WorkspaceAccessPolicy accessPolicy
        ) {
            if (looksAbsolute(pattern)) {
                return absolutePatternTarget(pattern, accessPolicy);
            }
            return new SearchTarget(pattern, resolvedPath);
        }

        private static boolean looksAbsolute(String pattern) {
            if (pattern == null || pattern.isBlank()) {
                return false;
            }
            return pattern.startsWith("/")
                    || pattern.startsWith("\\\\")
                    || pattern.matches("^[A-Za-z]:[\\\\/].*");
        }
    }
}
