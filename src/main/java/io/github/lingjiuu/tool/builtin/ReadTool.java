package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReadTool implements ToolDefinition {

    private final FileAccessPolicy accessPolicy;
    private final ReadToolLimits limits;

    public ReadTool(FileAccessPolicy accessPolicy) {
        this(accessPolicy, ReadToolLimits.defaults());
    }

    public ReadTool(FileAccessPolicy accessPolicy, ReadToolLimits limits) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        this.accessPolicy = accessPolicy;
        this.limits = limits;
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
        return "Read a text file from the local project filesystem.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Path to the text file to read. Relative paths resolve from the current project root."
                        ),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Optional one-based line number to start reading from."
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Optional maximum number of lines to return."
                        )
                ),
                "required", List.of("path")
        );
    }

    @Override
    public String promptSnippet() {
        return "Read text file contents";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use read to inspect project files before answering questions about code.");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        try {
            String pathArgument = String.valueOf(context.getArguments().get("path"));
            int offset = positiveInt(context.getArguments().get("offset"), 1, "offset");
            Integer requestedLimit = optionalPositiveInt(context.getArguments().get("limit"), "limit");
            Path resolvedPath = accessPolicy.resolveReadablePath(pathArgument);
            return readTextFile(pathArgument, resolvedPath, offset, requestedLimit);
        } catch (Exception e) {
            return ToolExecutionResult.errorText("Read failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult readTextFile(
            String requestedPath,
            Path resolvedPath,
            int offset,
            Integer requestedLimit
    ) throws IOException {
        if (Files.isDirectory(resolvedPath)) {
            throw new IOException("Path is a directory: " + requestedPath);
        }
        if (!Files.exists(resolvedPath)) {
            throw new IOException("File does not exist: " + requestedPath);
        }

        int lineCap = requestedLimit == null
                ? limits.maxLines()
                : Math.min(requestedLimit, limits.maxLines());
        ReadResult readResult = collectLines(resolvedPath, offset, lineCap);
        if (offset > readResult.totalLines() && readResult.totalLines() > 0) {
            throw new IllegalArgumentException("Offset " + offset + " is beyond end of file (" + readResult.totalLines() + " lines total).");
        }

        String text = renderResult(requestedPath, resolvedPath, offset, readResult);
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(text).getContents())
                .details(Map.of(
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "startLine", offset,
                        "endLine", readResult.endLine(),
                        "totalLines", readResult.totalLines(),
                        "returnedLines", readResult.returnedLines(),
                        "truncated", readResult.truncated()
                ))
                .error(false)
                .build();
    }

    private ReadResult collectLines(Path resolvedPath, int offset, int lineCap) throws IOException {
        StringBuilder output = new StringBuilder();
        int totalLines = 0;
        int returnedLines = 0;
        int endLine = Math.max(0, offset - 1);
        boolean truncatedByBytes = false;
        boolean truncatedByLines = false;
        boolean outputClosed = false;

        try (BufferedReader reader = Files.newBufferedReader(resolvedPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines < offset) {
                    continue;
                }
                if (outputClosed) {
                    continue;
                }
                if (returnedLines >= lineCap) {
                    truncatedByLines = true;
                    outputClosed = true;
                    continue;
                }

                String renderedLine = totalLines + "\t" + line;
                String nextChunk = output.isEmpty() ? renderedLine : "\n" + renderedLine;
                int nextBytes = output.toString().getBytes(StandardCharsets.UTF_8).length
                        + nextChunk.getBytes(StandardCharsets.UTF_8).length;
                if (nextBytes > limits.maxBytes()) {
                    truncatedByBytes = true;
                    outputClosed = true;
                    continue;
                }

                output.append(nextChunk);
                returnedLines++;
                endLine = totalLines;
            }
        }

        boolean hasMoreLines = endLine < totalLines;
        return new ReadResult(
                output.toString(),
                totalLines,
                returnedLines,
                returnedLines == 0 ? 0 : endLine,
                hasMoreLines || truncatedByBytes || truncatedByLines,
                truncatedByBytes,
                truncatedByLines
        );
    }

    private String renderResult(String requestedPath, Path resolvedPath, int offset, ReadResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("Read file: ").append(requestedPath).append('\n');
        builder.append("Resolved path: ").append(resolvedPath).append('\n');
        builder.append("Lines: ");
        if (result.returnedLines() == 0) {
            builder.append("none");
        } else {
            builder.append(offset).append("-").append(result.endLine());
        }
        builder.append(" of ").append(result.totalLines()).append('\n');
        builder.append('\n');
        if (result.content().isBlank()) {
            builder.append("[No content returned]");
        } else {
            builder.append(result.content());
        }
        if (result.truncated() && result.endLine() < result.totalLines()) {
            builder.append("\n\n[More content available. Use offset=")
                    .append(result.endLine() + 1)
                    .append(" to continue.]");
        }
        if (result.truncatedByBytes()) {
            builder.append("\n[Output truncated at ")
                    .append(limits.maxBytes())
                    .append(" bytes.]");
        }
        return builder.toString();
    }

    private int positiveInt(Object value, int defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        Integer parsed = optionalPositiveInt(value, name);
        return parsed == null ? defaultValue : parsed;
    }

    private Integer optionalPositiveInt(Object value, String name) {
        if (value == null) {
            return null;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            parsed = Integer.parseInt(String.valueOf(value));
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private record ReadResult(
            String content,
            int totalLines,
            int returnedLines,
            int endLine,
            boolean truncated,
            boolean truncatedByBytes,
            boolean truncatedByLines
    ) {
    }
}
