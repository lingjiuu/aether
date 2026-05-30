package io.github.lingjiuu.tool.builtin.read;

import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ReadTool implements Tool<ReadTool.Input, ReadTool.Output> {

    private static final int MAX_LINES_TO_READ = 2000;
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final int MAX_OUTPUT_TOKENS = 25_000;
    private static final String FILE_UNCHANGED_STUB =
            "File unchanged since last read. The content from the earlier Read tool_result in this conversation is still current — refer to that instead of re-reading.";
    private static final String CYBER_RISK_MITIGATION_REMINDER =
            "\n\n<system-reminder>\n"
                    + "Whenever you read a file, you should consider whether it would be considered malware. "
                    + "You CAN and SHOULD provide analysis of malware, what it is doing. "
                    + "But you MUST refuse to improve or augment the code. "
                    + "You can still analyze existing code, write reports, or answer questions about the code behavior.\n"
                    + "</system-reminder>\n";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tiff", ".tif",
            ".mp4", ".mov", ".avi", ".mkv", ".webm", ".wmv", ".flv", ".m4v", ".mpeg", ".mpg",
            ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".aiff", ".opus",
            ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".xz", ".z", ".tgz", ".iso",
            ".exe", ".dll", ".so", ".dylib", ".bin", ".o", ".a", ".obj", ".lib", ".app", ".msi", ".deb", ".rpm",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp",
            ".ttf", ".otf", ".woff", ".woff2", ".eot",
            ".pyc", ".pyo", ".class", ".jar", ".war", ".ear", ".node", ".wasm", ".rlib",
            ".sqlite", ".sqlite3", ".db", ".mdb", ".idx",
            ".psd", ".ai", ".eps", ".sketch", ".fig", ".xd", ".blend", ".3ds", ".max",
            ".swf", ".fla", ".lockb", ".dat", ".data"
    );

    private final WorkspaceAccessPolicy accessPolicy;

    public ReadTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String label() {
        return "Read";
    }

    @Override
    public String description() {
        return """
                Reads a file from the local filesystem. You can access any file directly by using this tool.
                Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.

                Usage:
                - The file_path parameter must be an absolute path, not a relative path
                - By default, it reads up to 2000 lines starting from the beginning of the file. Files larger than 256KB will return an error; use offset and limit for larger files
                - You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file by not providing these parameters
                - Results are returned using cat -n format, with line numbers starting at 1
                - This tool allows Claude Code to read images (eg PNG, JPG, etc). When reading an image file the contents are presented visually as Claude Code is a multimodal LLM.
                - This tool can only read files, not directories. To read a directory, use an ls command via the Bash tool.
                - You will regularly be asked to read screenshots. If the user provides a path to a screenshot, ALWAYS use this tool to view the file at the path. This tool will work with all temporary file paths.
                - If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents.\
                """;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "The absolute path to the file to read"),
                        "offset", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "description", "The line number to start reading from. Only provide if the file is too large to read at once"
                        ),
                        "limit", Map.of(
                                "type", "number",
                                "minimum", 1,
                                "description", "The number of lines to read. Only provide if the file is too large to read at once."
                        )
                ),
                "required", List.of("file_path"),
                "additionalProperties", false
        );
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of("oneOf", List.of(
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string", "enum", List.of("text")),
                                "file", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "filePath", Map.of("type", "string", "description", "The path to the file that was read"),
                                                "content", Map.of("type", "string", "description", "The content of the file"),
                                                "numLines", Map.of("type", "number", "description", "Number of lines in the returned content"),
                                                "startLine", Map.of("type", "number", "description", "The starting line number"),
                                                "totalLines", Map.of("type", "number", "description", "Total number of lines in the file")
                                        ),
                                        "required", List.of("filePath", "content", "numLines", "startLine", "totalLines")
                                )
                        ),
                        "required", List.of("type", "file")
                ),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string", "enum", List.of("image")),
                                "file", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "base64", Map.of("type", "string", "description", "Base64-encoded image data"),
                                                "type", Map.of(
                                                        "type", "string",
                                                        "enum", List.of("image/jpeg", "image/png", "image/gif", "image/webp"),
                                                        "description", "The MIME type of the image"
                                                ),
                                                "originalSize", Map.of("type", "number", "description", "Original file size in bytes"),
                                                "dimensions", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "originalWidth", Map.of("type", "number", "description", "Original image width in pixels"),
                                                                "originalHeight", Map.of("type", "number", "description", "Original image height in pixels"),
                                                                "displayWidth", Map.of("type", "number", "description", "Displayed image width in pixels (after resizing)"),
                                                                "displayHeight", Map.of("type", "number", "description", "Displayed image height in pixels (after resizing)")
                                                        ),
                                                        "description", "Image dimension info for coordinate mapping"
                                                )
                                        ),
                                        "required", List.of("base64", "type", "originalSize")
                                )
                        ),
                        "required", List.of("type", "file")
                ),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string", "enum", List.of("file_unchanged")),
                                "file", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "filePath", Map.of("type", "string", "description", "The path to the file")
                                        ),
                                        "required", List.of("filePath")
                                )
                        ),
                        "required", List.of("type", "file")
                )
        ));
    }

    @Override
    public Object prepareInput(Object input) {
        if (!(input instanceof Map<?, ?> map)) {
            return input;
        }
        Map<String, Object> prepared = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (("offset".equals(key) || "limit".equals(key)) && value instanceof String stringValue
                    && stringValue.matches("-?\\d+(\\.\\d+)?")) {
                value = Double.parseDouble(stringValue);
            }
            prepared.put(key, value);
        }
        return prepared;
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
        return Input.from(validateInputJson(argumentsJson));
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
        return switch (output.type()) {
            case "image" -> new ModelToolResult(List.of(ImageContent.builder()
                    .data(stringField(output.file(), "base64"))
                    .mimeType(stringField(output.file(), "type"))
                    .build()), false);
            case "file_unchanged" -> ModelToolResult.text(FILE_UNCHANGED_STUB);
            case "text" -> ModelToolResult.text(textToolResult(output.file()));
            default -> ModelToolResult.text("");
        };
    }

    @Override
    public ToolDisplayResult toDisplayResult(Output output, ToolResultContext<Input, Output> context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "read");
        details.put("path", stringField(output.file(), "filePath"));
        details.put("type", output.type());
        if ("text".equals(output.type())) {
            int numLines = intField(output.file(), "numLines", 0);
            int startLine = intField(output.file(), "startLine", 1);
            int totalLines = intField(output.file(), "totalLines", 0);
            details.put("fileType", "text");
            details.put("offset", startLine);
            details.put("returnedLines", numLines);
            details.put("totalLines", totalLines);
            details.put("hasMore", startLine + numLines - 1 < totalLines);
        } else if ("image".equals(output.type())) {
            details.put("fileType", "image");
            details.put("mimeType", stringField(output.file(), "type"));
            details.put("bytes", intField(output.file(), "originalSize", 0));
            details.put("image", true);
            details.put("omitted", false);
        } else if ("file_unchanged".equals(output.type())) {
            details.put("fileType", "file_unchanged");
        }
        return ToolDisplayResult.of("read", details);
    }

    private Output readFile(
            Input input,
            Path resolvedPath,
            ReadFileState readFileState
    ) throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("File does not exist.");
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + input.filePath());
        }
        if (!Files.isReadable(resolvedPath)) {
            throw new IOException("File is not readable: " + input.filePath());
        }

        String extension = extension(resolvedPath);
        String mimeType = ImageMimeDetector.detect(resolvedPath);
        if (mimeType != null) {
            return readImageFile(input.filePath(), resolvedPath, mimeType);
        }
        if (hasBinaryExtension(extension)) {
            throw new IOException("This tool cannot read binary files. The file appears to be a binary "
                    + extension + " file. Please use appropriate tools for binary file analysis.");
        }

        FileTime modifiedAt = Files.getLastModifiedTime(resolvedPath);
        ReadFileState.Snapshot snapshot = readFileState == null ? null : readFileState.get(resolvedPath);
        if (snapshot != null
                && snapshot.offset() != null
                && input.offset() == snapshot.offset()
                && sameLimit(input.limit(), snapshot.limit())
                && snapshot.sameModifiedAt(modifiedAt)) {
            return fileUnchanged(input.filePath());
        }

        long size = Files.size(resolvedPath);
        if (input.limit() == null && size > MAX_TEXT_BYTES) {
            throw new IOException("File content (" + formatFileSize(size)
                    + ") exceeds maximum allowed size (" + formatFileSize(MAX_TEXT_BYTES)
                    + "). Use offset and limit parameters to read specific portions of the file, or search for specific content instead of reading the whole file.");
        }

        byte[] bytes = Files.readAllBytes(resolvedPath);
        if (isBinaryContent(bytes)) {
            throw new IOException("This tool cannot read binary files. The file appears to contain binary data. Please use appropriate tools for binary file analysis.");
        }

        String content = stripBom(new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n"));
        List<String> lines = splitLines(content);
        int totalLines = lines.size();
        int startIndex = input.offset() == 0 ? 0 : input.offset() - 1;
        if (startIndex >= totalLines) {
            recordTextRead(readFileState, resolvedPath, content, false, modifiedAt, input.offset(), input.limit());
            return textOutput(input.filePath(), "", 0, input.offset(), totalLines);
        }

        int effectiveLimit = input.limit() == null ? MAX_LINES_TO_READ : input.limit();
        int selectedEnd = Math.min(totalLines, startIndex + effectiveLimit);
        List<String> selectedLines = lines.subList(startIndex, selectedEnd);
        String selectedContent = String.join("\n", selectedLines);
        validateOutputTokens(selectedContent);

        boolean fullRead = input.offset() == 1 && selectedEnd >= totalLines;
        recordTextRead(readFileState, resolvedPath, content, fullRead, modifiedAt, input.offset(), input.limit());
        return textOutput(input.filePath(), selectedContent, selectedLines.size(), input.offset(), totalLines);
    }

    private Output readImageFile(String requestedPath, Path resolvedPath, String mimeType) throws IOException {
        byte[] bytes = Files.readAllBytes(resolvedPath);
        if (bytes.length == 0) {
            throw new IOException("Image file is empty: " + requestedPath);
        }
        return new Output("image", mapOf(
                "base64", Base64.getEncoder().encodeToString(bytes),
                "type", mimeType,
                "originalSize", bytes.length
        ));
    }

    private void recordTextRead(
            ReadFileState readFileState,
            Path resolvedPath,
            String content,
            boolean fullRead,
            FileTime modifiedAt,
            int offset,
            Integer limit
    ) {
        if (readFileState == null) {
            return;
        }
        if (fullRead) {
            readFileState.recordFullRead(resolvedPath, content, modifiedAt, offset, limit);
        } else {
            readFileState.recordPartialRead(resolvedPath, modifiedAt, offset, limit);
        }
    }

    private Output textOutput(String filePath, String content, int numLines, int startLine, int totalLines) {
        return new Output("text", mapOf(
                "filePath", filePath,
                "content", content,
                "numLines", numLines,
                "startLine", startLine,
                "totalLines", totalLines
        ));
    }

    private Output fileUnchanged(String filePath) {
        return new Output("file_unchanged", mapOf("filePath", filePath));
    }

    private String textToolResult(Map<String, Object> file) {
        String content = stringField(file, "content");
        int totalLines = intField(file, "totalLines", 0);
        int startLine = intField(file, "startLine", 1);
        if (content == null || content.isEmpty()) {
            if (totalLines == 0) {
                return "<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>";
            }
            return "<system-reminder>Warning: the file exists but is shorter than the provided offset ("
                    + startLine + "). The file has " + totalLines + " lines.</system-reminder>";
        }
        return formatNumberedLines(content, startLine) + CYBER_RISK_MITIGATION_REMINDER;
    }

    private String formatNumberedLines(String content, int startLine) {
        List<String> lines = splitLines(content);
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            numbered.add((startLine + i) + "\t" + lines.get(i));
        }
        return String.join("\n", numbered);
    }

    private List<String> splitLines(String content) {
        String safeContent = content == null ? "" : content;
        if (safeContent.isEmpty()) {
            return List.of();
        }
        return List.of(safeContent.split("\n", -1));
    }

    private void validateOutputTokens(String content) {
        int tokenEstimate = roughTokenCount(content);
        if (tokenEstimate > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("File content (" + tokenEstimate
                    + " tokens) exceeds maximum allowed tokens (" + MAX_OUTPUT_TOKENS
                    + "). Use offset and limit parameters to read specific portions of the file, or search for specific content instead of reading the whole file.");
        }
    }

    private int roughTokenCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(content.length() / 4.0);
    }

    private String formatFileSize(long sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1) {
            return sizeInBytes + " bytes";
        }
        if (kb < 1024) {
            return trimTrailingZero(kb) + "KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return trimTrailingZero(mb) + "MB";
        }
        return trimTrailingZero(mb / 1024.0) + "GB";
    }

    private String trimTrailingZero(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }

    private boolean hasBinaryExtension(String extension) {
        return !extension.isBlank()
                && !IMAGE_EXTENSIONS.contains(extension.substring(1))
                && BINARY_EXTENSIONS.contains(extension);
    }

    private boolean isBinaryContent(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        int checkSize = Math.min(bytes.length, 8192);
        int nonPrintable = 0;
        for (int i = 0; i < checkSize; i++) {
            int value = bytes[i] & 0xff;
            if (value == 0) {
                return true;
            }
            if (value < 32 && value != 9 && value != 10 && value != 13) {
                nonPrintable++;
            }
        }
        return nonPrintable / (double) checkSize > 0.1;
    }

    private String extension(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String stripBom(String content) {
        return content != null && !content.isEmpty() && content.charAt(0) == '\uFEFF'
                ? content.substring(1)
                : content;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return stringValue;
    }

    private static Integer optionalInteger(Map<String, Object> arguments, String name, Integer defaultValue, int minimum) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double doubleValue = number.doubleValue();
        if (!Double.isFinite(doubleValue) || doubleValue % 1 != 0) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        int parsed = number.intValue();
        if (parsed < minimum) {
            throw new IllegalArgumentException(name + " must be >= " + minimum);
        }
        return parsed;
    }

    private boolean sameLimit(Integer requested, Integer snapshotLimit) {
        return requested == null ? snapshotLimit == null : requested.equals(snapshotLimit);
    }

    private static String stringField(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int intField(Map<String, Object> values, String key, int defaultValue) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    public record Input(String filePath, int offset, Integer limit) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "file_path"),
                    optionalInteger(arguments, "offset", 1, 0),
                    arguments.containsKey("limit") && arguments.get("limit") != null
                            ? optionalInteger(arguments, "limit", null, 1)
                            : null
            );
        }
    }

    public record Output(String type, Map<String, Object> file) {
        public Output {
            file = file == null ? Map.of() : Map.copyOf(file);
        }
    }
}
