package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class LsTool implements ToolDefinition {

    private final FileAccessPolicy accessPolicy;

    public LsTool(FileAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public String label() {
        return "ls";
    }

    @Override
    public String description() {
        return "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. "
                + "Includes dotfiles. Output is truncated to " + ToolOutputLimits.LS_DEFAULT_LIMIT
                + " entries or " + ToolOutputTruncator.formatSize(ToolOutputLimits.DEFAULT_MAX_BYTES)
                + " (whichever is hit first).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory to list (default: current directory)."
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Maximum number of entries to return (default: 500)."
                        )
                ),
                "required", List.of()
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
        return "List directory contents";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use ls to inspect directory contents before choosing files to search.");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        try {
            String requestedPath = BuiltinToolArguments.optionalString(context.getArguments(), "path", ".");
            int limit = BuiltinToolArguments.optionalPositiveInt(context.getArguments(), "limit", ToolOutputLimits.LS_DEFAULT_LIMIT);
            Path resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            return listDirectory(requestedPath, resolvedPath, limit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("ls failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult listDirectory(String requestedPath, Path resolvedPath, int limit) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw new IOException("Not a directory: " + requestedPath);
        }

        List<Path> entries;
        try (var stream = Files.list(resolvedPath)) {
            entries = stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }

        List<String> renderedEntries = new ArrayList<>();
        boolean entryLimitReached = false;
        for (Path entry : entries) {
            if (renderedEntries.size() >= limit) {
                entryLimitReached = true;
                break;
            }
            try {
                String suffix = Files.isDirectory(entry) ? "/" : "";
                renderedEntries.add(entry.getFileName() + suffix);
            } catch (RuntimeException ignored) {
                // Entries that disappear during listing should not fail the whole directory read.
            }
        }

        String rawOutput = renderedEntries.isEmpty() ? "(empty directory)" : String.join("\n", renderedEntries);
        ToolOutputTruncator.TruncationResult truncation = ToolOutputTruncator.truncateHead(rawOutput, ToolOutputLimits.DEFAULT_MAX_BYTES);
        String output = truncation.content();
        List<String> notices = new ArrayList<>();
        if (entryLimitReached) {
            notices.add(limit + " entries limit reached. Use limit=" + (limit * 2) + " for more");
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
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "returnedEntries", renderedEntries.size(),
                        "entryLimitReached", entryLimitReached,
                        "truncated", truncation.truncated()
                ))
                .error(false)
                .build();
    }
}
