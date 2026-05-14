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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReadTool implements ToolDefinition {

    private static final String OUTSIDE_WORKSPACE_DENIAL = "用户拒绝了此次调用";

    private final FileAccessPolicy accessPolicy;

    public ReadTool(FileAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String label() {
        return "read";
    }

    @Override
    public String description() {
        return "Read the contents of a workspace text file. Output is truncated to "
                + ToolOutputTruncator.formatSize(ToolOutputLimits.READ_MAX_BYTES)
                + ". Supports offset and limit for large files. When more content remains, continue with offset.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Workspace file path to read."),
                        "offset", Map.of("type", "integer", "description", "Line number to start reading from, 1-indexed."),
                        "limit", Map.of("type", "integer", "description", "Maximum number of lines to read.")
                ),
                "required", List.of("path")
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
        return "Read file contents";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use read to examine files found by ls, find, or grep.");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        String requestedPath;
        try {
            requestedPath = BuiltinToolArguments.requiredString(context.getArguments(), "path");
            int offset = BuiltinToolArguments.optionalPositiveInt(context.getArguments(), "offset", 1);
            Integer limit = optionalLimit(context.getArguments());
            Path resolvedPath;
            try {
                resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            } catch (IllegalArgumentException e) {
                if (isOutsideWorkspaceError(e)) {
                    return ToolExecutionResult.errorText(OUTSIDE_WORKSPACE_DENIAL);
                }
                throw e;
            }
            return readFile(requestedPath, resolvedPath, offset, limit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("read failed: " + e.getMessage());
        }
    }

    private Integer optionalLimit(Map<String, Object> arguments) {
        if (!arguments.containsKey("limit") || arguments.get("limit") == null) {
            return null;
        }
        return BuiltinToolArguments.optionalPositiveInt(arguments, "limit", 1);
    }

    private ToolExecutionResult readFile(String requestedPath, Path resolvedPath, int offset, Integer limit) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }
        if (!Files.isReadable(resolvedPath)) {
            throw new IOException("File is not readable: " + requestedPath);
        }

        String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        List<String> lines = splitLines(content);
        int totalLines = lines.size();
        int startIndex = offset - 1;
        if (startIndex >= totalLines) {
            throw new IOException("Offset " + offset + " is beyond end of file (" + totalLines + " lines total)");
        }

        int selectedEnd = limit == null ? totalLines : Math.min(totalLines, startIndex + limit);
        List<String> selectedLines = lines.subList(startIndex, selectedEnd);
        String selectedContent = String.join("\n", selectedLines);
        ToolOutputTruncator.TruncationResult truncation = ToolOutputTruncator.truncateHead(
                selectedContent,
                ToolOutputLimits.READ_MAX_BYTES
        );

        int returnedLines = truncation.outputLines();
        boolean truncated = truncation.truncated();
        boolean hasMore = selectedEnd < totalLines || truncated;
        String output;
        if (truncation.firstLineExceedsLimit()) {
            output = "[Line " + offset + " exceeds "
                    + ToolOutputTruncator.formatSize(ToolOutputLimits.READ_MAX_BYTES)
                    + " limit.]";
            returnedLines = 0;
            hasMore = true;
        } else {
            output = truncation.content();
            List<String> notices = new ArrayList<>();
            if (truncated) {
                int endLineDisplay = Math.max(offset, offset + returnedLines - 1);
                int nextOffset = endLineDisplay + 1;
                notices.add("Showing lines " + offset + "-" + endLineDisplay
                        + " of " + totalLines + ". Use offset=" + nextOffset + " to continue.");
            } else if (selectedEnd < totalLines) {
                int remaining = totalLines - selectedEnd;
                int nextOffset = selectedEnd + 1;
                notices.add(remaining + " more lines in file. Use offset=" + nextOffset + " to continue.");
            }
            if (!notices.isEmpty()) {
                output += "\n\n[" + String.join(". ", notices) + "]";
            }
        }

        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(output).getContents())
                .details(Map.of(
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "offset", offset,
                        "returnedLines", returnedLines,
                        "totalLines", totalLines,
                        "truncated", truncated,
                        "hasMore", hasMore
                ))
                .error(false)
                .build();
    }

    private boolean isOutsideWorkspaceError(IllegalArgumentException e) {
        return e.getMessage() != null && e.getMessage().contains("outside the allowed root");
    }

    private List<String> splitLines(String content) {
        String normalized = (content == null ? "" : content)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        return List.of(normalized.split("\n", -1));
    }
}
