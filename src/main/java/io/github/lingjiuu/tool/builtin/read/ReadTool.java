package io.github.lingjiuu.tool.builtin.read;

import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultLimits;
import io.github.lingjiuu.tool.result.ToolResultContext;
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

public class ReadTool implements Tool<ReadTool.Input, ReadTool.Output> {

    private static final int MAX_TEXT_BYTES = 24 * 1024;
    private static final int MAX_IMAGE_BASE64_BYTES = (int) (4.5 * 1024 * 1024);

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
                + ToolResultLimits.formatSize(MAX_TEXT_BYTES)
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
    public Input parseInput(String argumentsJson) {
        return Input.from(validateArguments(argumentsJson));
    }

    @Override
    public Map<String, Object> permissionArguments(Input input) {
        return Map.of("file_path", input.filePath());
    }

    @Override
    public ToolCallResult<Output> call(Input input, ToolUseContext context) {
        try {
            context.throwIfCancellationRequested();
            Path resolvedPath = accessPolicy.resolveReadablePath(input.filePath());
            Output result = readFile(input, resolvedPath, context.readFileState());
            context.throwIfCancellationRequested();
            return ToolCallResult.success(result);
        } catch (Exception e) {
            return ToolCallResult.failure(ToolFailure.runtime(e.getMessage()));
        }
    }

    @Override
    public ModelToolResult toModelResult(Output output, ToolResultContext<Input, Output> context) {
        return new ModelToolResult(output.contents(), false);
    }

    @Override
    public ToolDisplayResult toDisplayResult(Output output, ToolResultContext<Input, Output> context) {
        return ToolDisplayResult.of("read", output.details());
    }

    private Output readFile(
            Input input,
            Path resolvedPath,
            ReadFileState readFileState
    ) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + input.filePath());
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + input.filePath());
        }
        if (!Files.isReadable(resolvedPath)) {
            throw new IOException("File is not readable: " + input.filePath());
        }

        String mimeType = ImageMimeDetector.detect(resolvedPath);
        if (mimeType != null) {
            return readImageFile(input.filePath(), resolvedPath, mimeType);
        }

        String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        List<String> lines = splitLines(content);
        int totalLines = lines.size();
        if (totalLines == 0) {
            recordTextRead(readFileState, resolvedPath, content, true);
            return new Output(
                    List.of(TextContent.builder().text("[File is empty.]").build()),
                    textReadDetails(input.filePath(), resolvedPath, input.offset(), input.limit(), 0, 0, false, false)
            );
        }
        int startIndex = input.offset() - 1;
        if (startIndex >= totalLines) {
            throw new IOException("Offset " + input.offset() + " is beyond end of file (" + totalLines + " lines total)");
        }

        int selectedEnd = input.limit() == null ? totalLines : Math.min(totalLines, startIndex + input.limit());
        List<String> selectedLines = lines.subList(startIndex, selectedEnd);
        String selectedContent = String.join("\n", selectedLines);
        TextWindow window = TextWindow.fromHead(selectedContent, MAX_TEXT_BYTES);

        int returnedLines = window.lineCount();
        boolean truncated = window.truncated();
        boolean hasMore = selectedEnd < totalLines || truncated;
        String output;
        if (window.firstLineExceedsLimit()) {
            output = "[Line " + input.offset() + " exceeds "
                    + ToolResultLimits.formatSize(MAX_TEXT_BYTES)
                    + " limit.]";
            returnedLines = 0;
            hasMore = true;
        } else {
            output = formatNumberedLines(window.content(), input.offset(), returnedLines);
            List<String> notices = new ArrayList<>();
            if (truncated) {
                int endLineDisplay = Math.max(input.offset(), input.offset() + returnedLines - 1);
                int nextOffset = endLineDisplay + 1;
                notices.add("Showing lines " + input.offset() + "-" + endLineDisplay
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

        recordTextRead(readFileState, resolvedPath, content, input.offset() == 1 && !hasMore);
        return new Output(
                List.of(TextContent.builder().text(output).build()),
                textReadDetails(input.filePath(), resolvedPath, input.offset(), input.limit(), returnedLines, totalLines, truncated, hasMore)
        );
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

    private Output readImageFile(String requestedPath, Path resolvedPath, String mimeType) throws IOException {
        byte[] bytes = Files.readAllBytes(resolvedPath);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        int base64Bytes = base64.getBytes(StandardCharsets.UTF_8).length;
        String text = "Read image file [" + mimeType + "]";
        if (base64Bytes > MAX_IMAGE_BASE64_BYTES) {
            return new Output(
                    List.of(TextContent.builder().text(text
                            + "\n[Image omitted: inline image exceeds "
                            + ToolResultLimits.formatSize(MAX_IMAGE_BASE64_BYTES)
                            + " limit.]").build()),
                    Map.of(
                            "kind", "read",
                            "path", requestedPath,
                            "resolvedPath", resolvedPath.toString(),
                            "fileType", "image",
                            "mimeType", mimeType,
                            "bytes", bytes.length,
                            "base64Bytes", base64Bytes,
                            "image", true,
                            "omitted", true
                    )
            );
        }

        return new Output(
                List.of(
                        TextContent.builder().text(text).build(),
                        ImageContent.builder()
                                .data(base64)
                                .mimeType(mimeType)
                                .build()
                ),
                Map.of(
                        "kind", "read",
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "fileType", "image",
                        "mimeType", mimeType,
                        "bytes", bytes.length,
                        "base64Bytes", base64Bytes,
                        "image", true,
                        "omitted", false
                )
        );
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

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return stringValue;
    }

    private static int optionalPositiveInt(Map<String, Object> arguments, String name, int defaultValue) {
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
                throw new IllegalArgumentException(name + " must be a positive integer");
            }
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return parsed;
    }

    public record Input(String filePath, int offset, Integer limit) {
        static Input from(Map<String, Object> arguments) {
            Integer limit = arguments.containsKey("limit") && arguments.get("limit") != null
                    ? optionalPositiveInt(arguments, "limit", 1)
                    : null;
            return new Input(
                    requiredString(arguments, "file_path"),
                    optionalPositiveInt(arguments, "offset", 1),
                    limit
            );
        }
    }

    public record Output(List<MessageContent> contents, Map<String, Object> details) {
    }

    private record TextWindow(
            String content,
            int lineCount,
            boolean truncated,
            boolean firstLineExceedsLimit
    ) {
        static TextWindow fromHead(String content, int maxBytes) {
            String safeContent = content == null ? "" : content;
            List<String> lines = List.of(safeContent.split("\n", -1));
            if (byteLength(safeContent) <= maxBytes) {
                return new TextWindow(safeContent, lines.size(), false, false);
            }
            if (!lines.isEmpty() && byteLength(lines.getFirst()) > maxBytes) {
                return new TextWindow("", 0, true, true);
            }

            List<String> retained = new ArrayList<>();
            int retainedBytes = 0;
            for (String line : lines) {
                int lineBytes = byteLength(line) + (retained.isEmpty() ? 0 : 1);
                if (retainedBytes + lineBytes > maxBytes) {
                    break;
                }
                retained.add(line);
                retainedBytes += lineBytes;
            }
            return new TextWindow(String.join("\n", retained), retained.size(), true, false);
        }
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
