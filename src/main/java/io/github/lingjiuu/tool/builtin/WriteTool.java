package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.file.ReadFileState;
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
        return "Write a text file, creating parent directories as needed. "
                + "Existing files must be read first; prefer edit for small changes.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "File path to write."),
                        "content", Map.of("type", "string", "description", "UTF-8 text content to write.")
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
    public ToolExecutionResult execute(ToolInvocation context) {
        try {
            context.throwIfCancellationRequested();
            String requestedPath = ToolArguments.requiredString(context.getArguments(), "file_path");
            String content = requiredContent(context.getArguments());
            Path resolvedPath = accessPolicy.resolveWritablePath(requestedPath);

            boolean existedBefore = Files.exists(resolvedPath);
            if (existedBefore) {
                ensureExistingFileCanBeWritten(context.readFileState(), requestedPath, resolvedPath);
            }
            Path parent = resolvedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            context.throwIfCancellationRequested();
            Files.writeString(resolvedPath, content, StandardCharsets.UTF_8);
            recordWrite(context.readFileState(), resolvedPath, content);
            context.throwIfCancellationRequested();

            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", "write");
            details.put("path", requestedPath);
            details.put("resolvedPath", resolvedPath.toString());
            details.put("operation", existedBefore ? "update" : "create");
            details.put("chars", content.length());
            details.put("bytes", bytes);
            details.put("lineCount", lineCount(content));
            details.put("existedBefore", existedBefore);
            details.put("firstLine", firstLine(content));
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text(
                            "Successfully wrote " + content.length() + " chars to " + requestedPath
                    ).getContents())
                    .details(details)
                    .error(false)
                    .build();
        } catch (Exception e) {
            return ToolExecutionResult.errorText("write failed: " + e.getMessage());
        }
    }

    private String requiredContent(Map<String, Object> arguments) {
        Object value = arguments.get("content");
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("content must be a string");
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
        if (snapshot.partial()) {
            throw new IllegalStateException("File was only partially read. Read the full file before writing to it.");
        }

        String currentContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        if (!currentContent.equals(snapshot.content())) {
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
}
