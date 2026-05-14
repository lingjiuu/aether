package io.github.lingjiuu.tool.builtin;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import io.github.lingjiuu.tool.render.ToolRenderedOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditTool implements ToolDefinition {

    private static final String OUTSIDE_WORKSPACE_DENIAL = "用户拒绝了此次调用";

    private final FileAccessPolicy accessPolicy;

    public EditTool(FileAccessPolicy accessPolicy) {
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
        return "Edit one workspace text file by replacing one exact unique oldText with newText.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Workspace file path to edit."),
                        "oldText", Map.of("type", "string", "description", "Exact text to replace. Must appear once."),
                        "newText", Map.of("type", "string", "description", "Replacement text.")
                ),
                "required", List.of("path", "oldText", "newText"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolSourceInfo sourceInfo() {
        return ToolSourceInfo.builtin();
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public String promptSnippet() {
        return "Edit files with exact text replacement";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of(
                "Read the file before using edit.",
                "Use edit only when oldText appears exactly once."
        );
    }

    @Override
    public ToolRenderedOutput renderCall(ToolRenderRequest request) {
        return ToolRenderedOutput.text("edit " + stringArg(request, "path", "<path>"));
    }

    @Override
    public ToolRenderedOutput renderResult(ToolRenderRequest request) {
        if (request.toolResult() == null) {
            return null;
        }
        String text = MessageContents.text(request.toolResult());
        Object details = request.toolResult().getDetails();
        if (details instanceof Map<?, ?> detailsMap && detailsMap.get("diff") instanceof String diff && !diff.isBlank()) {
            return ToolRenderedOutput.text(text + "\n" + diff);
        }
        return ToolRenderedOutput.text(text);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        try {
            context.throwIfCancellationRequested();
            String requestedPath = BuiltinToolArguments.requiredString(context.getArguments(), "path");
            String oldText = requiredText(context.getArguments(), "oldText");
            String newText = requiredText(context.getArguments(), "newText");
            if (oldText.isEmpty()) {
                return ToolExecutionResult.errorText("edit failed: oldText must not be empty");
            }

            Path resolvedPath;
            try {
                resolvedPath = accessPolicy.resolveWritablePath(requestedPath);
            } catch (IllegalArgumentException e) {
                if (isOutsideWorkspaceError(e)) {
                    return ToolExecutionResult.errorText(OUTSIDE_WORKSPACE_DENIAL);
                }
                throw e;
            }
            ToolExecutionResult result = editFile(requestedPath, resolvedPath, oldText, newText);
            context.throwIfCancellationRequested();
            return result;
        } catch (Exception e) {
            return ToolExecutionResult.errorText("edit failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult editFile(String requestedPath, Path resolvedPath, String oldText, String newText)
            throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }

        String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        TextMutationSupport.TextState state = TextMutationSupport.capture(content);
        String normalizedOldText = TextMutationSupport.normalizeLineEndings(oldText);
        String normalizedNewText = TextMutationSupport.normalizeLineEndings(newText);
        int occurrences = TextMutationSupport.countOccurrences(state.normalizedContent(), normalizedOldText);
        if (occurrences == 0) {
            return ToolExecutionResult.errorText("edit failed: exact oldText not found");
        }
        if (occurrences > 1) {
            return ToolExecutionResult.errorText("edit failed: oldText matched multiple times; it must be unique");
        }

        String updated = state.normalizedContent().replace(normalizedOldText, normalizedNewText);
        if (updated.equals(state.normalizedContent())) {
            return ToolExecutionResult.errorText("edit failed: no changes were made");
        }

        Files.writeString(resolvedPath, state.restore(updated), StandardCharsets.UTF_8);
        int firstChangedLine = TextMutationSupport.firstChangedLine(state.normalizedContent(), normalizedOldText);
        String diff = TextMutationSupport.simpleDiff(normalizedOldText, normalizedNewText);
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text("Successfully replaced text in " + requestedPath).getContents())
                .details(Map.of(
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "oldTextChars", oldText.length(),
                        "newTextChars", newText.length(),
                        "replacements", 1,
                        "firstChangedLine", firstChangedLine,
                        "diff", diff
                ))
                .error(false)
                .build();
    }

    private String requiredText(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return stringValue;
    }

    private boolean isOutsideWorkspaceError(IllegalArgumentException e) {
        return e.getMessage() != null && e.getMessage().contains("outside the allowed root");
    }

    private String stringArg(ToolRenderRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : defaultValue;
    }
}
