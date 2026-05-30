package io.github.lingjiuu.tool.builtin.edit;

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

public class EditTool implements Tool<EditTool.Input, EditTool.Output> {

    private final WorkspaceAccessPolicy accessPolicy;

    public EditTool(WorkspaceAccessPolicy accessPolicy) {
        if (accessPolicy == null) {
            throw new IllegalArgumentException("accessPolicy must not be null");
        }
        this.accessPolicy = accessPolicy;
    }

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public String label() {
        return "Edit";
    }

    @Override
    public String description() {
        return """
                Performs exact string replacements in files.
                
                Usage:
                - You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.
                - When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: line number + tab. Everything after that is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.
                - ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
                - Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.
                - The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.
                - Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.\
                """;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "The absolute path to the file to modify"),
                        "old_string", Map.of("type", "string", "description", "The text to replace"),
                        "new_string", Map.of("type", "string", "description", "The text to replace it with (must be different from old_string)"),
                        "replace_all", Map.of("type", "boolean", "description", "Replace all occurrences of old_string (default false)")
                ),
                "required", List.of("file_path", "old_string", "new_string"),
                "additionalProperties", false
        );
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "filePath", Map.of("type", "string", "description", "The file path that was edited"),
                        "oldString", Map.of("type", "string", "description", "The original string that was replaced"),
                        "newString", Map.of("type", "string", "description", "The new string that replaced it"),
                        "originalFile", Map.of("type", "string", "description", "The original file contents before editing"),
                        "structuredPatch", Map.of(
                                "type", "array",
                                "items", StructuredDiff.hunkSchema(),
                                "description", "Diff patch showing the changes"
                        ),
                        "userModified", Map.of("type", "boolean", "description", "Whether the user modified the proposed changes"),
                        "replaceAll", Map.of("type", "boolean", "description", "Whether all occurrences were replaced"),
                        "gitDiff", StructuredDiff.gitDiffSchema()
                ),
                "required", List.of("filePath", "oldString", "newString", "originalFile", "structuredPatch", "userModified", "replaceAll")
        );
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
            if ("replace_all".equals(key) && value instanceof String stringValue) {
                if ("true".equals(stringValue)) {
                    value = true;
                } else if ("false".equals(stringValue)) {
                    value = false;
                }
            }
            prepared.put(key, value);
        }
        return prepared;
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
            if (input.oldString().equals(input.newString())) {
                throw new IllegalArgumentException("old_string and new_string must be different");
            }

            Path resolvedPath = accessPolicy.resolveWritablePath(input.filePath());
            Output result = editFile(
                    context.readFileState(),
                    input.filePath(),
                    resolvedPath,
                    input.oldString(),
                    input.newString(),
                    input.replaceAll()
            );
            context.throwIfCancellationRequested();
            return ToolCallResult.success(result);
        } catch (Exception e) {
            return ToolCallResult.failure(ToolFailure.runtime(e.getMessage()));
        }
    }

    @Override
    public ModelToolResult toModelResult(Output output, ToolResultContext<Input, Output> context) {
        String modifiedNote = output.userModified()
                ? ".  The user modified your proposed changes before accepting them. "
                : "";
        if (output.replaceAll()) {
            return ModelToolResult.text("The file " + output.filePath()
                    + " has been updated" + modifiedNote + ". All occurrences were successfully replaced.");
        }
        return ModelToolResult.text("The file " + output.filePath() + " has been updated successfully" + modifiedNote + ".");
    }

    @Override
    public ToolDisplayResult toDisplayResult(Output output, ToolResultContext<Input, Output> context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "edit");
        details.put("path", output.filePath());
        details.put("replaceAll", output.replaceAll());
        details.put("structuredPatch", output.structuredPatch());
        details.put("diffText", StructuredDiff.toDiffText(output.structuredPatch()));
        return ToolDisplayResult.of("edit", details);
    }

    private Output editFile(
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
            return createFile(readFileState, requestedPath, resolvedPath, oldString, newString);
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
                    state.normalizedContent(),
                    oldString,
                    newString,
                    newString,
                    false
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
                state.normalizedContent(),
                oldString,
                newString,
                applied.newContent(),
                replaceAll
        );
    }

    private Output createFile(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath,
            String oldString,
            String content
    ) throws IOException {
        Path parent = resolvedPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(resolvedPath, content, StandardCharsets.UTF_8);
        recordFileState(readFileState, resolvedPath, content);
        return new Output(
                requestedPath,
                oldString,
                content,
                "",
                StructuredDiff.patch("", content),
                false,
                false,
                null
        );
    }

    private Output writeAppliedContent(
            ReadFileState readFileState,
            String requestedPath,
            Path resolvedPath,
            EditApplier.TextState state,
            String originalFile,
            String oldString,
            String newString,
            String normalizedNewContent,
            boolean replaceAll
    ) throws IOException {
        String restored = state.restore(normalizedNewContent);
        Files.writeString(resolvedPath, restored, StandardCharsets.UTF_8);
        recordFileState(readFileState, resolvedPath, restored);
        return new Output(
                requestedPath,
                oldString,
                newString,
                originalFile,
                StructuredDiff.patch(originalFile, normalizedNewContent),
                false,
                replaceAll,
                null
        );
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

    public record Input(String filePath, String oldString, String newString, boolean replaceAll) {
        static Input from(Map<String, Object> arguments) {
            return new Input(
                    requiredString(arguments, "file_path"),
                    requiredString(arguments, "old_string"),
                    requiredString(arguments, "new_string"),
                    optionalBoolean(arguments, "replace_all", false)
            );
        }
    }

    public record Output(
            String filePath,
            String oldString,
            String newString,
            String originalFile,
            List<StructuredDiff.Hunk> structuredPatch,
            boolean userModified,
            boolean replaceAll,
            Map<String, Object> gitDiff
    ) {
    }
}
