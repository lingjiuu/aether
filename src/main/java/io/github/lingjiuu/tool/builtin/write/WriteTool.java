package io.github.lingjiuu.tool.builtin.write;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.builtin.diff.StructuredDiff;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WriteTool implements Tool<WriteTool.Input, WriteTool.Output> {

    private static final String READ_REQUIRED_MESSAGE = "File has not been read yet. Read it first before writing to it.";

    private final WorkspaceAccessPolicy accessPolicy;

    public WriteTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public String label() {
        return "Write";
    }

    @Override
    public String description() {
        return """
                Writes a file to the local filesystem.
                
                Usage:
                - This tool will overwrite the existing file if there is one at the provided path.
                - If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.
                - Prefer the Edit tool for modifying existing files — it only sends the diff. Only use this tool to create new files or for complete rewrites.
                - NEVER create documentation files (*.md) or README files unless explicitly requested by the User.
                - Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.\
                """;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "The absolute path to the file to write (must be absolute, not relative)"),
                        "content", Map.of("type", "string", "description", "The content to write to the file")
                ),
                "required", List.of("file_path", "content"),
                "additionalProperties", false
        );
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "type", Map.of(
                                "type", "string",
                                "enum", List.of("create", "update"),
                                "description", "Whether a new file was created or an existing file was updated"
                        ),
                        "filePath", Map.of("type", "string", "description", "The path to the file that was written"),
                        "content", Map.of("type", "string", "description", "The content that was written to the file"),
                        "structuredPatch", Map.of(
                                "type", "array",
                                "items", StructuredDiff.hunkSchema(),
                                "description", "Diff patch showing the changes"
                        ),
                        "originalFile", Map.of(
                                "type", List.of("string", "null"),
                                "description", "The original file content before the write (null for new files)"
                        ),
                        "gitDiff", StructuredDiff.gitDiffSchema()
                ),
                "required", List.of("type", "filePath", "content", "structuredPatch", "originalFile")
        );
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.withMaxResultSizeChars(100_000);
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
            Path resolvedPath = accessPolicy.resolveWritablePath(input.filePath());

            boolean existedBefore = Files.exists(resolvedPath);
            String originalFile = null;
            if (existedBefore) {
                originalFile = ensureExistingFileCanBeWritten(context.readFileState(), input.filePath(), resolvedPath);
            }
            Path parent = resolvedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            context.throwIfCancellationRequested();
            Files.writeString(resolvedPath, input.content(), StandardCharsets.UTF_8);
            recordWrite(context.readFileState(), resolvedPath, input.content());
            context.throwIfCancellationRequested();

            return ToolCallResult.success(new Output(
                    existedBefore ? "update" : "create",
                    input.filePath(),
                    input.content(),
                    existedBefore ? StructuredDiff.patch(originalFile, input.content()) : List.of(),
                    originalFile,
                    null
            ));
        } catch (Exception e) {
            if (READ_REQUIRED_MESSAGE.equals(e.getMessage())) {
                return ToolCallResult.failure(ToolFailure.validation(READ_REQUIRED_MESSAGE));
            }
            return ToolCallResult.failure(ToolFailure.runtime(e.getMessage()));
        }
    }

    @Override
    public ModelToolResult toModelResult(Output output, ToolResultContext<Input, Output> context) {
        if ("update".equals(output.type())) {
            return ModelToolResult.text("The file " + output.filePath() + " has been updated successfully.");
        }
        return ModelToolResult.text("File created successfully at: " + output.filePath());
    }

    @Override
    public ToolDisplayResult toDisplayResult(Output output, ToolResultContext<Input, Output> context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "write");
        details.put("path", output.filePath());
        details.put("operation", output.type());
        details.put("chars", output.content().length());
        details.put("bytes", output.content().getBytes(StandardCharsets.UTF_8).length);
        details.put("lineCount", lineCount(output.content()));
        details.put("existedBefore", "update".equals(output.type()));
        details.put("firstLine", firstLine(output.content()));
        details.put("structuredPatch", output.structuredPatch());
        return ToolDisplayResult.of("write", details);
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return stringValue;
    }

    private String ensureExistingFileCanBeWritten(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath
    ) throws IOException {
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }
        if (readFileState == null) {
            throw new IllegalStateException(READ_REQUIRED_MESSAGE);
        }
        ReadFileState.Snapshot snapshot = readFileState.get(resolvedPath);
        if (snapshot == null || snapshot.partial()) {
            throw new IllegalStateException(READ_REQUIRED_MESSAGE);
        }

        String currentContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        if (!snapshot.matchesCurrent(currentContent, Files.getLastModifiedTime(resolvedPath))) {
            throw new IllegalStateException("File has been modified since read. Read it again before writing to it.");
        }
        return currentContent;
    }

    private void recordWrite(ReadFileState readFileState, Path resolvedPath, String content) throws IOException {
        if (readFileState != null) {
            readFileState.recordFull(resolvedPath, content, Files.getLastModifiedTime(resolvedPath));
        }
    }

    private String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        int newline = normalized.indexOf('\n');
        return newline >= 0 ? normalized.substring(0, newline) : normalized;
    }

    private int lineCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        int lines = normalized.endsWith("\n") ? 0 : 1;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public record Input(String filePath, String content) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "file_path"),
                    requiredString(arguments, "content")
            );
        }
    }

    public record Output(
            String type,
            String filePath,
            String content,
            List<StructuredDiff.Hunk> structuredPatch,
            String originalFile,
            Map<String, Object> gitDiff
    ) {
    }
}
