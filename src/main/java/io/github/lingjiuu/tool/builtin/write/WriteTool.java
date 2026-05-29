package io.github.lingjiuu.tool.builtin.write;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WriteTool implements Tool {

    private final WorkspaceAccessPolicy accessPolicy;

    public WriteTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String label() {
        return "write";
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
                - Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "The absolute path to the file to write (must be absolute, not relative)."),
                        "content", Map.of("type", "string", "description", "The content to write to the file.")
                ),
                "required", List.of("file_path", "content"),
                "additionalProperties", false
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
    public ToolExecutionResult execute(ToolInvocation context) {
        try {
            context.throwIfCancellationRequested();
            Args args = Args.from(context.getArguments());
            Path resolvedPath = accessPolicy.resolveWritablePath(args.filePath());

            boolean existedBefore = Files.exists(resolvedPath);
            if (existedBefore) {
                ensureExistingFileCanBeWritten(context.readFileState(), args.filePath(), resolvedPath);
            }
            Path parent = resolvedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            context.throwIfCancellationRequested();
            Files.writeString(resolvedPath, args.content(), StandardCharsets.UTF_8);
            recordWrite(context.readFileState(), resolvedPath, args.content());
            context.throwIfCancellationRequested();

            int bytes = args.content().getBytes(StandardCharsets.UTF_8).length;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", "write");
            details.put("path", args.filePath());
            details.put("resolvedPath", resolvedPath.toString());
            details.put("operation", existedBefore ? "update" : "create");
            details.put("chars", args.content().length());
            details.put("bytes", bytes);
            details.put("lineCount", lineCount(args.content()));
            details.put("existedBefore", existedBefore);
            details.put("firstLine", firstLine(args.content()));
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(
                            "Successfully wrote " + args.content().length() + " chars to " + args.filePath()
                    ).getContents())
                    .details(details)
                    .error(false)
                    .build();
        } catch (Exception e) {
            return ToolExecutionResult.errorText("write failed: " + e.getMessage());
        }
    }

    private static String requiredNonBlankString(Map<String, Object> arguments, String name) {
        String value = requiredString(arguments, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return value;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return stringValue;
    }

    private void ensureExistingFileCanBeWritten(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath
    ) throws IOException {
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }
        if (readFileState == null) {
            throw new IllegalStateException("File has not been read yet. Read it first before writing to it.");
        }
        ReadFileState.Snapshot snapshot = readFileState.get(resolvedPath);
        if (snapshot == null) {
            throw new IllegalStateException("File has not been read yet. Read it first before writing to it.");
        }

        String currentContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        if (!snapshot.matchesCurrent(currentContent, Files.getLastModifiedTime(resolvedPath))) {
            throw new IllegalStateException("File has been modified since read. Read it again before writing to it.");
        }
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

    private record Args(String filePath, String content) {
        static Args from(Map<String, Object> arguments) {
            return new Args(
                    requiredNonBlankString(arguments, "file_path"),
                    requiredString(arguments, "content")
            );
        }
    }
}
