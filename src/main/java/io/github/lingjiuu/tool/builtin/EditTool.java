package io.github.lingjiuu.tool.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EditTool implements ToolDefinition {

    private static final String OUTSIDE_WORKSPACE_DENIAL = "用户拒绝了此次调用";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

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
        return "Edit one workspace text file using one or more exact text replacements. "
                + "Every edits[].oldText must be unique in the original file and edits must not overlap.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> replacementSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "oldText", Map.of("type", "string", "description", "Exact text to replace. Must appear once."),
                        "newText", Map.of("type", "string", "description", "Replacement text.")
                ),
                "required", List.of("oldText", "newText"),
                "additionalProperties", false
        );
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Workspace file path to edit."),
                        "edits", Map.of(
                                "type", "array",
                                "description", "One or more targeted replacements. Each oldText must be unique in the original file and edits must not overlap.",
                                "items", replacementSchema
                        )
                ),
                "required", List.of("path", "edits"),
                "additionalProperties", false
        );
    }

    @Override
    public Object prepareArguments(Object arguments) {
        if (!(arguments instanceof Map<?, ?> input)) {
            return arguments;
        }

        Map<String, Object> prepared = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                prepared.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        Object editsValue = prepared.get("edits");
        if (editsValue instanceof String editsJson) {
            try {
                JsonNode parsed = OBJECT_MAPPER.readTree(editsJson);
                if (parsed != null && parsed.isArray()) {
                    prepared.put("edits", OBJECT_MAPPER.convertValue(parsed, new TypeReference<List<Object>>() {
                    }));
                    editsValue = prepared.get("edits");
                }
            } catch (Exception ignored) {
            }
        }

        Object oldText = prepared.get("oldText");
        Object newText = prepared.get("newText");
        if (oldText instanceof String oldTextValue && newText instanceof String newTextValue) {
            List<Object> edits = editsValue instanceof List<?> existing
                    ? new ArrayList<>(existing)
                    : new ArrayList<>();
            edits.add(Map.of("oldText", oldTextValue, "newText", newTextValue));
            prepared.put("edits", edits);
            prepared.remove("oldText");
            prepared.remove("newText");
        }

        return prepared;
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
        return "Make precise file edits with exact text replacement, including multiple disjoint edits in one call";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of(
                "Read the file before using edit.",
                "When changing multiple separate locations in one file, use one edit call with multiple entries in edits[].",
                "Each edits[].oldText is matched against the original file, not after earlier edits are applied.",
                "Do not emit overlapping or nested edits; merge nearby changes into one replacement.",
                "Keep edits[].oldText as small as possible while still unique in the file."
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
            List<TextMutationSupport.Edit> edits = requiredEdits(context.getArguments());

            Path resolvedPath;
            try {
                resolvedPath = accessPolicy.resolveWritablePath(requestedPath);
            } catch (IllegalArgumentException e) {
                if (isOutsideWorkspaceError(e)) {
                    return ToolExecutionResult.errorText(OUTSIDE_WORKSPACE_DENIAL);
                }
                throw e;
            }
            ToolExecutionResult result = editFile(requestedPath, resolvedPath, edits);
            context.throwIfCancellationRequested();
            return result;
        } catch (Exception e) {
            return ToolExecutionResult.errorText("edit failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult editFile(String requestedPath, Path resolvedPath, List<TextMutationSupport.Edit> edits)
            throws IOException {
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Path not found: " + requestedPath);
        }
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("Not a file: " + requestedPath);
        }

        String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
        TextMutationSupport.TextState state = TextMutationSupport.capture(content);
        TextMutationSupport.AppliedEdits applied = TextMutationSupport.applyEditsToNormalizedContent(
                state.normalizedContent(),
                edits,
                requestedPath
        );

        Files.writeString(resolvedPath, state.restore(applied.newContent()), StandardCharsets.UTF_8);
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(
                        "Successfully replaced " + applied.replacements() + " block(s) in " + requestedPath + "."
                ).getContents())
                .details(Map.of(
                        "path", requestedPath,
                        "resolvedPath", resolvedPath.toString(),
                        "replacements", applied.replacements(),
                        "firstChangedLine", applied.firstChangedLine(),
                        "diff", applied.diff()
                ))
                .error(false)
                .build();
    }

    private List<TextMutationSupport.Edit> requiredEdits(Map<String, Object> arguments) {
        Object value = arguments.get("edits");
        if (!(value instanceof List<?> rawEdits)) {
            throw new IllegalArgumentException("edits must be an array");
        }
        if (rawEdits.isEmpty()) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }

        List<TextMutationSupport.Edit> edits = new ArrayList<>();
        for (int i = 0; i < rawEdits.size(); i++) {
            Object rawEdit = rawEdits.get(i);
            if (!(rawEdit instanceof Map<?, ?> editMap)) {
                throw new IllegalArgumentException("edits[" + i + "] must be an object");
            }
            Object oldText = editMap.get("oldText");
            Object newText = editMap.get("newText");
            if (!(oldText instanceof String oldTextValue)) {
                throw new IllegalArgumentException("edits[" + i + "].oldText must be a string");
            }
            if (!(newText instanceof String newTextValue)) {
                throw new IllegalArgumentException("edits[" + i + "].newText must be a string");
            }
            edits.add(new TextMutationSupport.Edit(oldTextValue, newTextValue));
        }
        return edits;
    }

    private boolean isOutsideWorkspaceError(IllegalArgumentException e) {
        return e.getMessage() != null && e.getMessage().contains("outside the allowed root");
    }

    private String stringArg(ToolRenderRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : defaultValue;
    }
}
