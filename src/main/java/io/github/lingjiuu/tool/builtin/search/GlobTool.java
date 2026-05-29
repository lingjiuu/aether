package io.github.lingjiuu.tool.builtin.search;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolRiskLevel;
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

public class GlobTool implements Tool {

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
        return "glob";
    }

    @Override
    public String label() {
        return "glob";
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
    public Map<String, Object> parametersSchema() {
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
    public ToolExecutionResult execute(ToolInvocation context) {
        try {
            context.throwIfCancellationRequested();
            Args args = Args.from(context.getArguments());
            Path resolvedPath = accessPolicy.resolveReadablePath(args.path());
            var rgPath = Ripgrep.command();
            if (rgPath.isEmpty()) {
                return ToolExecutionResult.errorText("glob failed: ripgrep (rg) is unavailable");
            }
            return glob(context, rgPath.get(), args.pattern(), args.path(), resolvedPath);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("glob failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult glob(
            ToolInvocation context,
            String rgPath,
            String pattern,
            String requestedPath,
            Path resolvedPath
    ) throws IOException, InterruptedException {
        context.throwIfCancellationRequested();
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
        args.add(pattern);
        args.add("--sortr=modified");
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

            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(output).getContents())
                    .details(details)
                    .error(false)
                    .build();
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

    private record Args(String pattern, String path) {
        static Args from(Map<String, Object> arguments) {
            return new Args(
                    requiredString(arguments, "pattern"),
                    optionalString(arguments, "path", ".")
            );
        }
    }
}
