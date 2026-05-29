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
        return """
                Performs exact string replacements in files.
                
                Usage:
                - You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.
                - When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: 'line number + tab'. Everything after that is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.
                - ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
                - Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.
                - The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.
                - Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string", "description", "The absolute path to the file to modify."),
                        "old_string", Map.of("type", "string", "description", "The text to replace."),
                        "new_string", Map.of("type", "string", "description", "The text to replace it with (must be different from old_string)."),
                        "replace_all", Map.of("type", "boolean", "description", "Replace all occurrences of old_string (default false)")
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
