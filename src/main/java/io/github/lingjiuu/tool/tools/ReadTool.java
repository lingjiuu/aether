package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import io.github.lingjiuu.tool.render.ToolRenderedOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ReadTool implements ToolDefinition {

    private static final String OUTSIDE_WORKSPACE_DENIAL = "用户拒绝了此次调用";

    private final WorkspaceAccessPolicy accessPolicy;

    public ReadTool(WorkspaceAccessPolicy accessPolicy) {
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
        return "Read the contents of a workspace file. Supports text files and images (jpg, png, gif, webp). "
                + "Images are sent as attachments. For text files, output is truncated to "
                + TextOutputTruncator.formatSize(ToolOutputLimits.READ_MAX_BYTES)
                + ". Supports offset and limit for large text files. When more text remains, continue with offset.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Workspace file path to read."),
                        "offset", Map.of("type", "integer", "minimum", 1, "description", "Line number to start reading from, 1-indexed."),
                        "limit", Map.of("type", "integer", "minimum", 1, "description", "Maximum number of lines to read.")
                ),
                "required", List.of("path"),
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
        return "Read file contents";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use read to examine files found by ls, find, or grep.");
    }

    @Override
    public ToolRenderedOutput renderCall(ToolRenderRequest request) {
        String path = stringArg(request, "path", "<path>");
        Object offset = request.arguments().get("offset");
        Object limit = request.arguments().get("limit");
        StringBuilder text = new StringBuilder("read ").append(path);
        if (offset != null) {
            text.append(" offset=").append(offset);
        }
        if (limit != null) {
            text.append(" limit=").append(limit);
        }
        return ToolRenderedOutput.text(text.toString());
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
        String requestedPath;
        try {
            context.throwIfCancellationRequested();
            requestedPath = ToolArguments.requiredString(context.getArguments(), "path");
            Path resolvedPath;
            try {
                resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            } catch (IllegalArgumentException e) {
                if (isOutsideWorkspaceError(e)) {
                    return ToolExecutionResult.errorText(OUTSIDE_WORKSPACE_DENIAL);
                }
                throw e;
            }
            ToolExecutionResult result = readFile(requestedPath, resolvedPath, context.getArguments());
            context.throwIfCancellationRequested();
            return result;
        } catch (Exception e) {
            return ToolExecutionResult.errorText("read failed: " + e.getMessage());
        }
    }

    private Integer optionalLimit(Map<String, Object> arguments) {
        if (!arguments.containsKey("limit") || arguments.get("limit") == null) {
            return null;
        }
        return ToolArguments.optionalPositiveInt(arguments, "limit", 1);
    }

    private ToolExecutionResult readFile(String requestedPath, Path resolvedPath, Map<String, Object> arguments) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }
        if (!Files.isReadable(resolvedPath)) {
            throw new IOException("File is not readable: " + requestedPath);
        }

        String mimeType = ImageMimeDetector.detect(resolvedPath);
        if (mimeType != null) {
            return readImageFile(requestedPath, resolvedPath, mimeType);
        }

        int offset = ToolArguments.optionalPositiveInt(arguments, "offset", 1);
        Integer limit = optionalLimit(arguments);
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
        TextOutputTruncator.TruncationResult truncation = TextOutputTruncator.truncateHead(
                selectedContent,
                ToolOutputLimits.READ_MAX_BYTES
        );

        int returnedLines = truncation.outputLines();
        boolean truncated = truncation.truncated();
        boolean hasMore = selectedEnd < totalLines || truncated;
        String output;
        if (truncation.firstLineExceedsLimit()) {
            output = "[Line " + offset + " exceeds "
                    + TextOutputTruncator.formatSize(ToolOutputLimits.READ_MAX_BYTES)
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

    private ToolExecutionResult readImageFile(String requestedPath, Path resolvedPath, String mimeType) throws IOException {
        byte[] bytes = Files.readAllBytes(resolvedPath);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        int base64Bytes = base64.getBytes(StandardCharsets.UTF_8).length;
        String text = "Read image file [" + mimeType + "]";
        if (base64Bytes > ToolOutputLimits.READ_MAX_IMAGE_BASE64_BYTES) {
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(text
                            + "\n[Image omitted: inline image exceeds "
                            + TextOutputTruncator.formatSize(ToolOutputLimits.READ_MAX_IMAGE_BASE64_BYTES)
                            + " limit.]").getContents())
                    .details(Map.of(
                            "path", requestedPath,
                            "resolvedPath", resolvedPath.toString(),
                            "mimeType", mimeType,
                            "bytes", bytes.length,
                            "base64Bytes", base64Bytes,
                            "image", true,
                            "omitted", true
                    ))
                    .error(false)
                    .build();
        }

        return ToolExecutionResult.builder()
                .contents(List.of(
                        TextContent.builder().text(text).build(),
                        ImageContent.builder()
                                .data(base64)
                                .mimeType(mimeType)
                                .build()
                ))
                .details(Map.of(
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "mimeType", mimeType,
                        "bytes", bytes.length,
                        "base64Bytes", base64Bytes,
                        "image", true,
                        "omitted", false
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

    private String stringArg(ToolRenderRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : defaultValue;
    }
}
