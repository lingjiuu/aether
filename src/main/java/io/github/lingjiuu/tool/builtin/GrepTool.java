package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.tool.fs.WorkspaceIgnoreMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GrepTool implements ToolDefinition {

    private final FileAccessPolicy accessPolicy;

    public GrepTool(FileAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
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
        return "Search file contents for a pattern. Returns matching lines with file paths and line numbers. Respects .gitignore.";
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
                        "context", Map.of("type", "integer", "description", "Number of lines to show before and after each match (default: 0)."),
                        "limit", Map.of("type", "integer", "description", "Maximum number of matches to return (default: 100).")
                ),
                "required", List.of("pattern")
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
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        try {
            String pattern = BuiltinToolArguments.requiredString(context.getArguments(), "pattern");
            String requestedPath = BuiltinToolArguments.optionalString(context.getArguments(), "path", ".");
            String glob = BuiltinToolArguments.optionalString(context.getArguments(), "glob", null);
            boolean ignoreCase = BuiltinToolArguments.optionalBoolean(context.getArguments(), "ignoreCase", false);
            boolean literal = BuiltinToolArguments.optionalBoolean(context.getArguments(), "literal", false);
            int contextLines = BuiltinToolArguments.optionalNonNegativeInt(context.getArguments(), "context", 0);
            int limit = BuiltinToolArguments.optionalPositiveInt(context.getArguments(), "limit", ToolOutputLimits.GREP_DEFAULT_LIMIT);
            Path resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            return grep(pattern, requestedPath, resolvedPath, glob, ignoreCase, literal, contextLines, limit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("grep failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult grep(
            String pattern,
            String requestedPath,
            Path resolvedPath,
            String glob,
            boolean ignoreCase,
            boolean literal,
            int contextLines,
            int limit
    ) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }

        List<Path> candidates = candidateFiles(resolvedPath, glob);
        SearchMatcher searchMatcher = SearchMatcher.create(pattern, literal, ignoreCase);
        List<Match> matches = new ArrayList<>();
        for (Path file : candidates) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                if (searchMatcher.matches(lines.get(i))) {
                    matches.add(new Match(file, i + 1, lines));
                    if (matches.size() >= limit) {
                        return render(pattern, requestedPath, resolvedPath, glob, matches, true, contextLines);
                    }
                }
            }
        }
        return render(pattern, requestedPath, resolvedPath, glob, matches, false, contextLines);
    }

    private List<Path> candidateFiles(Path resolvedPath, String glob) throws IOException {
        PathGlobMatcher globMatcher = glob == null || glob.isBlank() ? null : PathGlobMatcher.of(glob);
        if (Files.isRegularFile(resolvedPath)) {
            String name = resolvedPath.getFileName().toString();
            if (globMatcher == null || globMatcher.matches(name)) {
                return List.of(resolvedPath);
            }
            return List.of();
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw new IOException("Not a file or directory: " + resolvedPath);
        }

        WorkspaceIgnoreMatcher ignoreMatcher = WorkspaceIgnoreMatcher.load(resolvedPath);
        List<Path> candidates = new ArrayList<>();
        Files.walkFileTree(resolvedPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(resolvedPath) && ignoreMatcher.isIgnored(dir, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || ignoreMatcher.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = toPosix(resolvedPath.relativize(file));
                if (globMatcher == null || globMatcher.matches(relative)) {
                    candidates.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        candidates.sort(Comparator.comparing(path -> toPosix(resolvedPath.relativize(path)).toLowerCase(Locale.ROOT)));
        return candidates;
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
            int start = contextLines > 0 ? Math.max(1, match.lineNumber() - contextLines) : match.lineNumber();
            int end = contextLines > 0 ? Math.min(match.lines().size(), match.lineNumber() + contextLines) : match.lineNumber();
            for (int lineNumber = start; lineNumber <= end; lineNumber++) {
                String lineText = match.lines().get(lineNumber - 1).replace("\r", "");
                ToolOutputTruncator.LineTruncation line = ToolOutputTruncator.truncateLine(lineText, ToolOutputLimits.GREP_MAX_LINE_LENGTH);
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
            notices.add("Some lines truncated to " + ToolOutputLimits.GREP_MAX_LINE_LENGTH + " chars");
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
            return toPosix(searchPath.relativize(file));
        }
        return file.getFileName().toString();
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record Match(Path file, int lineNumber, List<String> lines) {
    }

    private interface SearchMatcher {
        boolean matches(String line);

        static SearchMatcher create(String pattern, boolean literal, boolean ignoreCase) {
            if (literal) {
                String needle = ignoreCase ? pattern.toLowerCase(Locale.ROOT) : pattern;
                return line -> {
                    String haystack = ignoreCase ? line.toLowerCase(Locale.ROOT) : line;
                    return haystack.contains(needle);
                };
            }
            try {
                int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                Pattern compiled = Pattern.compile(pattern, flags);
                return line -> compiled.matcher(line).find();
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("pattern must be a valid regex or use literal=true: " + e.getMessage());
            }
        }
    }
}
