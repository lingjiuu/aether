package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WriteTool implements ToolDefinition {

    private static final String OUTSIDE_WORKSPACE_DENIAL = "用户拒绝了此次调用";

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
        return "Create or overwrite a workspace text file, creating parent directories as needed.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Workspace file path to write."),
                        "content", Map.of("type", "string", "description", "UTF-8 text content to write.")
                ),
                "required", List.of("path", "content"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public String promptSnippet() {
        return "Create or overwrite files";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of(
                "Use write for new files or complete rewrites.",
                "Prefer edit for targeted changes to existing files."
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context) {
        try {
            context.throwIfCancellationRequested();
            String requestedPath = ToolArguments.requiredString(context.getArguments(), "path");
            String content = requiredContent(context.getArguments());
            Path resolvedPath;
            try {
                resolvedPath = accessPolicy.resolveWritablePath(requestedPath);
            } catch (IllegalArgumentException e) {
                if (isOutsideWorkspaceError(e)) {
                    return ToolExecutionResult.errorText(OUTSIDE_WORKSPACE_DENIAL);
                }
                throw e;
            }

            boolean existedBefore = Files.exists(resolvedPath);
            Path parent = resolvedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            context.throwIfCancellationRequested();
            Files.writeString(resolvedPath, content, StandardCharsets.UTF_8);
            context.throwIfCancellationRequested();

            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", "write");
            details.put("path", requestedPath);
            details.put("resolvedPath", resolvedPath.toString());
            details.put("operation", existedBefore ? "overwrite" : "create");
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

    private boolean isOutsideWorkspaceError(IllegalArgumentException e) {
        return e.getMessage() != null && e.getMessage().contains("outside the allowed root");
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
