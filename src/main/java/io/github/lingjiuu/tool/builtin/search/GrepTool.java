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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class GrepTool implements Tool<GrepTool.Input, GrepTool.Output> {

    private static final String MODE_FILES_WITH_MATCHES = "files_with_matches";
    private static final String MODE_CONTENT = "content";
    private static final String MODE_COUNT = "count";
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int MAX_LINE_LENGTH = 500;

    private final WorkspaceAccessPolicy accessPolicy;

    public GrepTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String label() {
        return "Grep";
    }

    @Override
    public String description() {
        return """
                A powerful search tool built on ripgrep
                
                  Usage:
                  - ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.
                  - Supports full regex syntax (e.g., "log.*Error", "function\\\\s+\\\\w+")
                  - Filter files with glob parameter (e.g., "*.js", "**/*.tsx") or type parameter (e.g., "js", "py", "rust")
                  - Output modes: "content" shows matching lines, "files_with_matches" shows only file paths (default), "count" shows match counts
                  - Pattern syntax: Uses ripgrep (not grep) - literal braces need escaping (use `interface\\\\{\\\\}` to find `interface{}` in Go code)
                  - Multiline matching: By default patterns match within single lines only. For cross-line patterns like `struct \\\\{[\\\\s\\\\S]*?field`, use `multiline: true`
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("pattern", Map.of(
                                "type", "string",
                                "description", "The regular expression pattern to search for in file contents"
                        )),
                        Map.entry("path", Map.of(
                                "type", "string",
                                "description", "File or directory to search in (rg PATH). Defaults to current working directory."
                        )),
                        Map.entry("glob", Map.of(
                                "type", "string",
                                "description", "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob"
                        )),
                        Map.entry("output_mode", Map.of(
                                "type", "string",
                                "enum", List.of(MODE_CONTENT, MODE_FILES_WITH_MATCHES, MODE_COUNT),
                                "description", "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\"."
                        )),
                        Map.entry("-B", Map.of("type", "number", "description", "Number of lines to show before each match (rg -B). Requires output_mode: \"content\", ignored otherwise.")),
                        Map.entry("-A", Map.of("type", "number", "description", "Number of lines to show after each match (rg -A). Requires output_mode: \"content\", ignored otherwise.")),
                        Map.entry("-C", Map.of("type", "number", "description", "Alias for context.")),
                        Map.entry("context", Map.of("type", "number", "description", "Number of lines to show before and after each match (rg -C). Requires output_mode: \"content\", ignored otherwise.")),
                        Map.entry("-n", Map.of("type", "boolean", "description", "Show line numbers in output (rg -n). Requires output_mode: \"content\", ignored otherwise. Defaults to true.")),
                        Map.entry("-i", Map.of("type", "boolean", "description", "Case insensitive search (rg -i)")),
                        Map.entry("type", Map.of("type", "string", "description", "File type to search (rg --type). Common types: js, py, rust, go, java, etc. More efficient than include for standard file types.")),
                        Map.entry("head_limit", Map.of(
                                "type", "number",
                                "description", "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults to 250 when unspecified. Pass 0 for unlimited (use sparingly — large result sets waste context)."
                        )),
                        Map.entry("offset", Map.of("type", "number", "description", "Skip first N lines/entries before applying head_limit, equivalent to \"| tail -n +N | head -N\". Works across all output modes. Defaults to 0.")),
                        Map.entry("multiline", Map.of("type", "boolean", "description", "Enable multiline mode where . matches newlines and patterns can span lines (rg -U --multiline-dotall). Default: false."))
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
    public Object prepareArguments(Object arguments) {
        if (!(arguments instanceof Map<?, ?> map)) {
            return arguments;
        }
        Map<String, Object> prepared = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            prepared.put(key, prepareSemanticArgument(key, entry.getValue()));
        }
        return prepared;
    }

    @Override
    public ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.withMaxResultSizeChars(20_000);
    }

    @Override
    public Input parseInput(String argumentsJson) {
        return Input.from(validateArguments(argumentsJson));
    }

    @Override
    public ToolCallResult<Output> call(Input input, ToolUseContext context) {
        try {
            context.throwIfCancellationRequested();
            Path resolvedPath = accessPolicy.resolveReadablePath(input.path());
            if (!Files.exists(resolvedPath)) {
                throw new IOException("Path not found: " + input.path());
            }

            var rgPath = Ripgrep.command();
            if (rgPath.isEmpty()) {
                return ToolCallResult.failure(ToolFailure.runtime("ripgrep (rg) is unavailable"));
            }

            return ToolCallResult.success(grep(context, rgPath.get(), input.pattern(), input.path(), resolvedPath, input.options()));
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
        return ToolDisplayResult.of("grep", output.details());
    }

    private Output grep(
            ToolUseContext context,
            String rgPath,
            String pattern,
            String requestedPath,
            Path resolvedPath,
            SearchOptions options
    ) throws IOException, InterruptedException {
        List<String> args = buildRipgrepArgs(rgPath, pattern, resolvedPath, options);
        Process process = new ProcessBuilder(args)
                .redirectErrorStream(false)
                .directory(accessPolicy.root().toFile())
                .start();
        AutoCloseable cancelRegistration = context.cancellationToken().onCancel(process::destroyForcibly);
        try {
            List<String> stdout = readLines(process.getInputStream());
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

            return switch (options.outputMode()) {
                case MODE_CONTENT -> renderContent(pattern, requestedPath, resolvedPath, options, stdout);
                case MODE_COUNT -> renderCount(pattern, requestedPath, resolvedPath, options, stdout);
                case MODE_FILES_WITH_MATCHES -> renderFilesWithMatches(pattern, requestedPath, resolvedPath, options, stdout);
                default -> throw new IOException("Invalid output_mode: " + options.outputMode());
            };
        } finally {
            closeQuietly(cancelRegistration);
        }
    }

    private List<String> buildRipgrepArgs(
            String rgPath,
            String pattern,
            Path resolvedPath,
            SearchOptions options
    ) {
        List<String> args = new ArrayList<>();
        args.add(rgPath);
        args.add("--hidden");
        args.add("--color=never");
        args.add("--max-columns");
        args.add(String.valueOf(MAX_LINE_LENGTH));
        for (String ignoredVcsDir : List.of("!.git/**", "!.svn/**", "!.hg/**", "!.bzr/**", "!.jj/**", "!.sl/**")) {
            args.add("--glob");
            args.add(ignoredVcsDir);
        }
        if (options.ignoreCase()) {
            args.add("-i");
        }
        if (options.multiline()) {
            args.add("-U");
            args.add("--multiline-dotall");
        }
        if (MODE_FILES_WITH_MATCHES.equals(options.outputMode())) {
            args.add("-l");
        } else if (MODE_COUNT.equals(options.outputMode())) {
            args.add("-c");
        } else if (options.lineNumbers()) {
            args.add("-n");
        }
        addContextArgs(args, options);
        if (options.type() != null) {
            args.add("--type");
            args.add(options.type());
        }
        for (String glob : splitGlob(options.glob())) {
            args.add("--glob");
            args.add(glob);
        }
        if (pattern.startsWith("-")) {
            args.add("-e");
            args.add(pattern);
        } else {
            args.add(pattern);
        }
        args.add(resolvedPath.toString());
        return args;
    }

    private void addContextArgs(List<String> args, SearchOptions options) {
        if (!MODE_CONTENT.equals(options.outputMode())) {
            return;
        }
        if (options.contextLines() > 0) {
            args.add("-C");
            args.add(String.valueOf(options.contextLines()));
            return;
        }
        if (options.beforeLines() > 0) {
            args.add("-B");
            args.add(String.valueOf(options.beforeLines()));
        }
        if (options.afterLines() > 0) {
            args.add("-A");
            args.add(String.valueOf(options.afterLines()));
        }
    }

    private Output renderFilesWithMatches(
            String pattern,
            String requestedPath,
            Path resolvedPath,
            SearchOptions options,
            List<String> stdout
    ) {
        List<String> filenames = stdout.stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(this::displayPath)
                .distinct()
                .sorted(modifiedTimeDescending())
                .toList();
        Page<String> page = page(filenames, options);
        String output = page.items().isEmpty()
                ? "No files found"
                : "Found " + filenames.size() + " " + plural(filenames.size(), "file", "files")
                + "\n" + String.join("\n", page.items());
        output = appendPagination(output, page);

        Map<String, Object> details = baseDetails(pattern, requestedPath, resolvedPath, options);
        details.put("mode", MODE_FILES_WITH_MATCHES);
        details.put("numFiles", page.items().size());
        details.put("totalFiles", filenames.size());
        details.put("filenames", List.copyOf(page.items()));
        details.put("truncated", page.truncated());
        return result(output, details);
    }

    private Output renderContent(
            String pattern,
            String requestedPath,
            Path resolvedPath,
            SearchOptions options,
            List<String> stdout
    ) {
        List<String> lines = stdout.stream()
                .map(this::displayLine)
                .toList();
        Page<String> page = page(lines, options);
        String rawOutput = page.items().isEmpty() ? "No matches found" : String.join("\n", page.items());
        String output = appendPagination(rawOutput, page);

        Map<String, Object> details = baseDetails(pattern, requestedPath, resolvedPath, options);
        details.put("mode", MODE_CONTENT);
        details.put("numLines", page.items().size());
        details.put("totalLines", lines.size());
        details.put("content", List.copyOf(page.items()));
        details.put("truncated", page.truncated());
        return result(output, details);
    }

    private Output renderCount(
            String pattern,
            String requestedPath,
            Path resolvedPath,
            SearchOptions options,
            List<String> stdout
    ) {
        List<CountLine> counts = stdout.stream()
                .map(this::parseCountLine)
                .filter(count -> count.count() > 0)
                .toList();
        List<String> lines = counts.stream()
                .map(count -> count.path() + ":" + count.count())
                .toList();
        int totalMatches = counts.stream().mapToInt(CountLine::count).sum();
        Page<String> page = page(lines, options);
        String output = page.items().isEmpty()
                ? "No matches found"
                : String.join("\n", page.items())
                + "\n\nFound " + totalMatches + " " + plural(totalMatches, "occurrence", "occurrences")
                + " across " + counts.size() + " " + plural(counts.size(), "file", "files") + ".";
        output = appendPagination(output, page);

        Map<String, Object> details = baseDetails(pattern, requestedPath, resolvedPath, options);
        details.put("mode", MODE_COUNT);
        details.put("numMatches", totalMatches);
        details.put("numFiles", counts.size());
        details.put("content", List.copyOf(page.items()));
        details.put("truncated", page.truncated());
        return result(output, details);
    }

    private Map<String, Object> baseDetails(
            String pattern,
            String requestedPath,
            Path resolvedPath,
            SearchOptions options
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "grep");
        details.put("pattern", pattern);
        details.put("path", requestedPath);
        details.put("glob", options.glob() == null ? "" : options.glob());
        details.put("resolvedPath", resolvedPath.toString());
        details.put("outputMode", options.outputMode());
        details.put("headLimit", options.headLimit());
        details.put("offset", options.offset());
        return details;
    }

    private Output result(String output, Map<String, Object> details) {
        return new Output(output, details);
    }

    private String appendPagination(String output, Page<?> page) {
        if (page.offset() <= 0 && !page.truncated()) {
            return output;
        }
        List<String> parts = new ArrayList<>();
        if (page.truncated() && page.limit() > 0) {
            parts.add("limit: " + page.limit());
        }
        if (page.offset() > 0) {
            parts.add("offset: " + page.offset());
        }
        if (page.truncated()) {
            parts.add("more results available");
        }
        return output + "\n\n[Showing results with pagination = " + String.join(", ", parts) + "]";
    }

    private <T> Page<T> page(List<T> items, SearchOptions options) {
        int offset = Math.min(options.offset(), items.size());
        int end = items.size();
        if (options.headLimit() > 0) {
            end = Math.min(items.size(), offset + options.headLimit());
        }
        return new Page<>(
                List.copyOf(items.subList(offset, end)),
                offset,
                options.headLimit(),
                end < items.size()
        );
    }

    private Comparator<String> modifiedTimeDescending() {
        return Comparator
                .comparingLong((String path) -> {
                    try {
                        Path resolved = accessPolicy.root().resolve(path).toAbsolutePath().normalize();
                        return Files.getLastModifiedTime(resolved).toMillis();
                    } catch (IOException | RuntimeException e) {
                        return 0L;
                    }
                })
                .reversed()
                .thenComparing(path -> path);
    }

    private String displayLine(String line) {
        String cleaned = line.replace("\r", "");
        if ("--".equals(cleaned)) {
            return cleaned;
        }
        int index = cleaned.indexOf(':');
        if (index < 0) {
            index = cleaned.indexOf('-');
        }
        if (index <= 0) {
            return cleaned;
        }
        String path = cleaned.substring(0, index);
        return displayPath(path) + cleaned.substring(index);
    }

    private CountLine parseCountLine(String line) {
        String cleaned = line.replace("\r", "").trim();
        int index = cleaned.lastIndexOf(':');
        if (index <= 0 || index == cleaned.length() - 1) {
            return new CountLine(displayPath(cleaned), 0);
        }
        int count;
        try {
            count = Integer.parseInt(cleaned.substring(index + 1));
        } catch (NumberFormatException e) {
            count = 0;
        }
        return new CountLine(displayPath(cleaned.substring(0, index)), count);
    }

    private String displayPath(String outputLine) {
        Path path = Path.of(outputLine);
        Path absolutePath = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : accessPolicy.root().resolve(path).toAbsolutePath().normalize();
        if (absolutePath.startsWith(accessPolicy.root())) {
            return toPosix(accessPolicy.root().relativize(absolutePath));
        }
        return outputLine;
    }

    private List<String> splitGlob(String glob) {
        if (glob == null || glob.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : glob.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.contains("{") && token.contains("}")) {
                values.add(token);
                continue;
            }
            for (String part : token.split(",")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        return List.copyOf(values);
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

    private void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static String plural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
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

    private static int optionalNonNegativeInt(Map<String, Object> arguments, String name, int defaultValue) {
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
                throw new IllegalArgumentException(name + " must be a non-negative integer");
            }
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
        return parsed;
    }

    private static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new IllegalArgumentException(name + " must be a boolean");
    }

    private static Object prepareSemanticArgument(String key, Object value) {
        if (!(value instanceof String stringValue)) {
            return value;
        }
        if (Set.of("-B", "-A", "-C", "context", "head_limit", "offset").contains(key)
                && stringValue.matches("-?\\d+(\\.\\d+)?")) {
            return Double.parseDouble(stringValue);
        }
        if (Set.of("-n", "-i", "multiline").contains(key)) {
            if ("true".equals(stringValue)) {
                return true;
            }
            if ("false".equals(stringValue)) {
                return false;
            }
        }
        return value;
    }

    public record Input(String pattern, String path, SearchOptions options) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "pattern"),
                    optionalString(arguments, "path", "."),
                    SearchOptions.from(arguments)
            );
        }
    }

    public record Output(String text, Map<String, Object> details) {
    }

    private record SearchOptions(
            String outputMode,
            String glob,
            int beforeLines,
            int afterLines,
            int contextLines,
            boolean lineNumbers,
            boolean ignoreCase,
            String type,
            int headLimit,
            int offset,
            boolean multiline
    ) {
        private static SearchOptions from(Map<String, Object> arguments) {
            String outputMode = optionalString(arguments, "output_mode", MODE_FILES_WITH_MATCHES);
            return new SearchOptions(
                    outputMode,
                    optionalString(arguments, "glob", null),
                    optionalNonNegativeInt(arguments, "-B", 0),
                    optionalNonNegativeInt(arguments, "-A", 0),
                    contextLines(arguments),
                    optionalBoolean(arguments, "-n", true),
                    optionalBoolean(arguments, "-i", false),
                    optionalString(arguments, "type", null),
                    optionalNonNegativeInt(arguments, "head_limit", DEFAULT_HEAD_LIMIT),
                    optionalNonNegativeInt(arguments, "offset", 0),
                    optionalBoolean(arguments, "multiline", false)
            );
        }

        private static int contextLines(Map<String, Object> arguments) {
            if (arguments.containsKey("context")) {
                return optionalNonNegativeInt(arguments, "context", 0);
            }
            return optionalNonNegativeInt(arguments, "-C", 0);
        }
    }

    private record Page<T>(List<T> items, int offset, int limit, boolean truncated) {
    }

    private record CountLine(String path, int count) {
    }
}
