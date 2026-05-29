package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReadTool implements Tool {

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
        return "Read the contents of a file. Supports text files and images (jpg, png, gif, webp). "
                + "Text results are returned with line numbers; do not include those line numbers when editing. "
                + "Images are sent as attachments. For text files, output is truncated to "
                + TextOutputTruncator.formatSize(ToolOutputLimits.READ_MAX_BYTES)
                + ". Supports offset and limit for large text files. When more text remains, continue with offset.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "The absolute path to the file to read."),
                        "offset", Map.of("type", "integer", "minimum", 1, "description", "The line number to start reading from. Only provide if the file is too large to read at once."),
                        "limit", Map.of("type", "integer", "minimum", 1, "description", "The number of lines to read. Only provide if the file is too large to read at once.")
                ),
                "required", List.of("file_path"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.neverPersist();
    }

    @Override
    public ToolExecutionResult execute(ToolInvocation context) {
        String requestedPath;
        try {
            context.throwIfCancellationRequested();
            requestedPath = ToolArguments.requiredString(context.getArguments(), "file_path");
            Path resolvedPath = accessPolicy.resolveReadablePath(requestedPath);
            ToolExecutionResult result = readFile(requestedPath, resolvedPath, context.getArguments(), context.readFileState());
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

    private ToolExecutionResult readFile(
            String requestedPath,
            Path resolvedPath,
            Map<String, Object> arguments,
            ReadFileState readFileState
    ) throws IOException {
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
        if (totalLines == 0) {
            recordTextRead(readFileState, resolvedPath, content, true);
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text("[File is empty.]").getContents())
                    .details(textReadDetails(requestedPath, resolvedPath, offset, limit, 0, 0, false, false))
                    .error(false)
                    .build();
        }
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
            output = formatNumberedLines(truncation.content(), offset, returnedLines);
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

        recordTextRead(readFileState, resolvedPath, content, offset == 1 && !hasMore);
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(output).getContents())
                .details(textReadDetails(requestedPath, resolvedPath, offset, limit, returnedLines, totalLines, truncated, hasMore))
                .error(false)
                .build();
    }

    private void recordTextRead(ReadFileState readFileState, Path resolvedPath, String content, boolean fullRead) throws IOException {
        if (readFileState == null) {
            return;
        }
        if (fullRead) {
            readFileState.recordFull(resolvedPath, content, Files.getLastModifiedTime(resolvedPath));
        } else {
            readFileState.recordPartial(resolvedPath, Files.getLastModifiedTime(resolvedPath));
        }
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
                            "kind", "read",
                            "path", requestedPath,
                            "resolvedPath", resolvedPath.toString(),
                            "fileType", "image",
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
                        "kind", "read",
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "fileType", "image",
                        "mimeType", mimeType,
                        "bytes", bytes.length,
                        "base64Bytes", base64Bytes,
                        "image", true,
                        "omitted", false
                ))
                .error(false)
                .build();
    }

    private Map<String, Object> textReadDetails(
            String requestedPath,
            Path resolvedPath,
            int offset,
            Integer limit,
            int returnedLines,
            int totalLines,
            boolean truncated,
            boolean hasMore
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "read");
        details.put("path", requestedPath);
        details.put("resolvedPath", resolvedPath.toString());
        details.put("fileType", "text");
        details.put("offset", offset);
        if (limit != null) {
            details.put("limit", limit);
        }
        details.put("returnedLines", returnedLines);
        details.put("totalLines", totalLines);
        details.put("truncated", truncated);
        details.put("hasMore", hasMore);
        return details;
    }

    private String formatNumberedLines(String content, int startLine, int lineCount) {
        List<String> lines = new ArrayList<>(splitLines(content));
        while (lines.size() < lineCount) {
            lines.add("");
        }
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            numbered.add(String.format(java.util.Locale.ROOT, "%6d\t%s", startLine + i, lines.get(i)));
        }
        return String.join("\n", numbered);
    }

    private List<String> splitLines(String content) {
        String normalized = (content == null ? "" : content)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] parts = normalized.split("\n", -1);
        if (normalized.endsWith("\n")) {
            return List.of(java.util.Arrays.copyOf(parts, parts.length - 1));
        }
        return List.of(parts);
    }
}
