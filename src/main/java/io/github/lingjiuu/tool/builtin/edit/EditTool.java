package io.github.lingjiuu.tool.builtin.edit;

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
import java.util.List;
import java.util.Map;

public class EditTool implements Tool {

    private final WorkspaceAccessPolicy accessPolicy;

    public EditTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String label() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit one text file using an exact string replacement. "
                + "The file must be read first unless old_string is empty for creating an empty or missing file. "
                + "old_string must be unique unless replace_all is true.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "File path to edit."),
                        "old_string", Map.of("type", "string", "description", "Exact text to replace."),
                        "new_string", Map.of("type", "string", "description", "Replacement text. Must differ from old_string."),
                        "replace_all", Map.of("type", "boolean", "description", "Replace all occurrences of old_string. Defaults to false.")
                ),
                "required", List.of("file_path", "old_string", "new_string"),
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
            if (args.oldString().equals(args.newString())) {
                throw new IllegalArgumentException("old_string and new_string must be different");
            }

            Path resolvedPath = accessPolicy.resolveWritablePath(args.filePath());
            ToolExecutionResult result = editFile(
                    context.readFileState(),
                    args.filePath(),
                    resolvedPath,
                    args.oldString(),
                    args.newString(),
                    args.replaceAll()
            );
            context.throwIfCancellationRequested();
            return result;
        } catch (Exception e) {
            return ToolExecutionResult.errorText("edit failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult editFile(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath,
            String oldString,
            String newString,
            boolean replaceAll
    )
            throws IOException {
        if (!Files.exists(resolvedPath)) {
            if (!oldString.isEmpty()) {
                throw new IOException("Path not found: " + requestedPath);
            }
            return createFile(readFileState, requestedPath, resolvedPath, newString);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }

        String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        EditApplier.TextState state = EditApplier.capture(content);
        if (oldString.isEmpty()) {
            if (!state.normalizedContent().isEmpty()) {
                throw new IllegalArgumentException("Cannot create new file - file already exists.");
            }
            return writeAppliedContent(
                    readFileState,
                    requestedPath,
                    resolvedPath,
                    state,
                    newString,
                    1,
                    1,
                    "create",
                    false,
                    EditApplier.simpleDiff("", newString)
            );
        }

        ensureFileWasRead(readFileState, resolvedPath, content);
        EditApplier.AppliedEdit applied = EditApplier.apply(
                state.normalizedContent(),
                oldString,
                newString,
                replaceAll,
                requestedPath
        );

        return writeAppliedContent(
                readFileState,
                requestedPath,
                resolvedPath,
                state,
                applied.newContent(),
                applied.replacements(),
                applied.firstChangedLine(),
                "update",
                replaceAll,
                applied.diff()
        );
    }

    private ToolExecutionResult createFile(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath,
            String content
    ) throws IOException {
        Path parent = resolvedPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(resolvedPath, content, StandardCharsets.UTF_8);
        recordFileState(readFileState, resolvedPath, content);
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text("Created " + requestedPath + ".").getContents())
                .details(Map.of(
                        "kind", "edit",
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "operation", "create",
                        "editCount", 1,
                        "firstChangedLine", 1,
                        "replaceAll", false,
                        "diffText", EditApplier.simpleDiff("", content)
                ))
                .error(false)
                .build();
    }

    private ToolExecutionResult writeAppliedContent(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath,
            EditApplier.TextState state,
            String normalizedNewContent,
            int replacements,
            int firstChangedLine,
            String operation,
            boolean replaceAll,
            String diffText
    ) throws IOException {
        String restored = state.restore(normalizedNewContent);
        Files.writeString(resolvedPath, restored, StandardCharsets.UTF_8);
        recordFileState(readFileState, resolvedPath, restored);
        String resultText = "create".equals(operation)
                ? "Created " + requestedPath + "."
                : "Successfully replaced " + replacements + " block(s) in " + requestedPath + ".";
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(resultText).getContents())
                .details(Map.of(
                        "kind", "edit",
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "operation", operation,
                        "editCount", replacements,
                        "firstChangedLine", firstChangedLine,
                        "replaceAll", replaceAll,
                        "diffText", diffText
                ))
                .error(false)
                .build();
    }

    private void ensureFileWasRead(
            ReadFileState readFileState,
            Path resolvedPath,
            String currentContent
    ) throws IOException {
        if (readFileState == null) {
            throw new IllegalStateException("File has not been read yet. Read it first before editing.");
        }
        ReadFileState.Snapshot snapshot = readFileState.get(resolvedPath);
        if (snapshot == null) {
            throw new IllegalStateException("File has not been read yet. Read it first before editing.");
        }
        if (!snapshot.matchesCurrent(currentContent, Files.getLastModifiedTime(resolvedPath))) {
            throw new IllegalStateException("File has been modified since read. Read it again before editing.");
        }
    }

    private void recordFileState(ReadFileState readFileState, Path resolvedPath, String content) throws IOException {
        if (readFileState != null) {
            readFileState.recordFull(resolvedPath, content, Files.getLastModifiedTime(resolvedPath));
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

    private static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new IllegalArgumentException(name + " must be a boolean");
    }

    private record Args(String filePath, String oldString, String newString, boolean replaceAll) {
        static Args from(Map<String, Object> arguments) {
            return new Args(
                    requiredNonBlankString(arguments, "file_path"),
                    requiredString(arguments, "old_string"),
                    requiredString(arguments, "new_string"),
                    optionalBoolean(arguments, "replace_all", false)
            );
        }
    }
}
