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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class FindTool implements ToolDefinition {

    private final FileAccessPolicy accessPolicy;

    public FindTool(FileAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
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
        return "Search for files by glob pattern. Returns matching file paths relative to the search directory. Respects .gitignore.";
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
                                "description", "Maximum number of results (default: 1000)."
                        )
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
        return "Find files by glob pattern (respects .gitignore)";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use find to discover candidate files before searching or inspecting them.");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        try {
            String pattern = BuiltinToolArguments.requiredString(context.getArguments(), "pattern");
            String requestedPath = BuiltinToolArguments.optionalString(context.getArguments(), "path", ".");
            int limit = BuiltinToolArguments.optionalPositiveInt(context.getArguments(), "limit", ToolOutputLimits.FIND_DEFAULT_LIMIT);
            Path resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            return findFiles(pattern, requestedPath, resolvedPath, limit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("find failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult findFiles(String pattern, String requestedPath, Path resolvedPath, int limit) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw new IOException("Not a directory: " + requestedPath);
        }

        PathGlobMatcher globMatcher = PathGlobMatcher.of(pattern);
        WorkspaceIgnoreMatcher ignoreMatcher = WorkspaceIgnoreMatcher.load(resolvedPath);
        List<String> matches = new ArrayList<>();
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
                if (globMatcher.matches(relative)) {
                    matches.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(String::toLowerCase));
        boolean resultLimitReached = matches.size() > limit;
        List<String> returned = matches.size() > limit ? matches.subList(0, limit) : matches;
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
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }
}
