package io.github.lingjiuu.tool.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolResultProcessor {

    public static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";
    public static final String PERSISTED_OUTPUT_CLOSING_TAG = "</persisted-output>";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ContextBuilder contextBuilder;
    private final ToolArtifactStore artifactStore;

    public ToolResultProcessor(ContextBuilder contextBuilder, ToolArtifactStore artifactStore) {
        this.contextBuilder = contextBuilder == null ? new ContextBuilder() : contextBuilder;
        if (artifactStore == null) {
            throw new IllegalArgumentException("artifactStore must not be null");
        }
        this.artifactStore = artifactStore;
    }

    public List<ProcessedToolResult> processBatch(List<ToolResultInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<Processed> processed = new ArrayList<>(inputs.size());
        for (ToolResultInput input : inputs) {
            processed.add(processOne(input));
        }
        applyAggregateBudget(processed);
        return processed.stream().map(Processed::result).toList();
    }

    private Processed processOne(ToolResultInput input) {
        List<ToolResultArtifactRef> artifactRefs = new ArrayList<>();
        ToolExecutionResult result = input.executionResult() == null
                ? ToolExecutionResult.errorText("Tool returned no result.")
                : input.executionResult();
        ToolResultMessage message = contextBuilder.toolResultMessage(input.toolCall(), result);
        ToolResultPolicy policy = policy(input.tool());
        message = ensureNonEmptyContent(message);
        message = maybePersistContent(input, message, policy, "output", policy.effectiveThreshold(), artifactRefs);
        Object sanitizedDetails = sanitizeDetails(message.getDetails(), safeToolCallId(input), "details", artifactRefs);
        message = copy(message, message.messageContents(), sanitizedDetails);
        return new Processed(input, policy, message, artifactRefs);
    }

    private void applyAggregateBudget(List<Processed> processed) {
        List<Candidate> candidates = new ArrayList<>();
        long total = 0L;
        for (int i = 0; i < processed.size(); i++) {
            Processed current = processed.get(i);
            if (!eligibleForAggregate(current)) {
                continue;
            }
            String text = MessageContents.text(current.message());
            long size = text.length();
            candidates.add(new Candidate(i, size));
            total += size;
        }
        if (total <= ToolResultLimits.MAX_TOOL_RESULTS_PER_BATCH_CHARS) {
            return;
        }
        candidates.sort(Comparator.comparingLong(Candidate::size).reversed());
        for (Candidate candidate : candidates) {
            if (total <= ToolResultLimits.MAX_TOOL_RESULTS_PER_BATCH_CHARS) {
                break;
            }
            Processed current = processed.get(candidate.index());
            ToolResultMessage replaced = maybePersistContent(
                    current.input(),
                    current.message(),
                    current.policy(),
                    "batch-output",
                    0L,
                    current.artifactRefs()
            );
            processed.set(candidate.index(), new Processed(
                    current.input(),
                    current.policy(),
                    replaced,
                    current.artifactRefs()
            ));
            total = total - candidate.size() + MessageContents.text(replaced).length();
        }
    }

    private boolean eligibleForAggregate(Processed processed) {
        if (!processed.policy().includeInAggregateBudget() || !processed.policy().persistLargeText()) {
            return false;
        }
        ToolResultMessage message = processed.message();
        if (hasImage(message.messageContents())) {
            return false;
        }
        String text = MessageContents.text(message);
        return !text.isBlank() && !isPersistedOutput(text);
    }

    private ToolResultMessage maybePersistContent(
            ToolResultInput input,
            ToolResultMessage message,
            ToolResultPolicy policy,
            String suffix,
            long threshold,
            List<ToolResultArtifactRef> artifactRefs
    ) {
        if (!policy.persistLargeText() || hasImage(message.messageContents())) {
            return message;
        }
        String text = MessageContents.text(message);
        if (text.isBlank() || isPersistedOutput(text) || text.length() <= threshold) {
            return message;
        }
        PersistedToolOutput persisted = persistMainOutput(input, text, policy, suffix);
        artifactRefs.add(artifactRef("tool_result_output", suffix, persisted));
        String replacement = buildPersistedOutputMessage(persisted, policy.previewMode());
        return copy(message, textContents(replacement), message.getDetails());
    }

    private PersistedToolOutput persistMainOutput(
            ToolResultInput input,
            String text,
            ToolResultPolicy policy,
            String suffix
    ) {
        Path sourcePath = mainOutputSourcePath(input.executionResult() == null ? null : input.executionResult().getDetails());
        if (sourcePath != null && Files.isRegularFile(sourcePath)) {
            return artifactStore.persistTextFile(
                    safeToolCallId(input),
                    suffix,
                    sourcePath,
                    policy.previewMode()
            );
        }
        return artifactStore.persistText(
                safeToolCallId(input),
                suffix,
                text,
                policy.previewMode()
        );
    }

    private Path mainOutputSourcePath(Object details) {
        if (!(details instanceof Map<?, ?> map)) {
            return null;
        }
        Object path = map.get("aggregateFullOutputPath");
        if (path == null) {
            path = map.get("fullOutputPath");
        }
        if (path instanceof String text && !text.isBlank()) {
            return Path.of(text);
        }
        return null;
    }

    private Object sanitizeDetails(
            Object value,
            String toolCallId,
            String suffix,
            List<ToolResultArtifactRef> artifactRefs
    ) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            if (byteLength(text) <= ToolResultLimits.DETAIL_VALUE_MAX_BYTES) {
                return text;
            }
            PersistedToolOutput persisted = artifactStore.persistText(toolCallId, suffix, text, ToolResultPreviewMode.HEAD);
            artifactRefs.add(artifactRef("tool_result_detail", suffix, persisted));
            return detailReference(persisted);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, sanitizeDetails(entry.getValue(), toolCallId, suffix + "-" + key, artifactRefs));
            }
            if (jsonSize(sanitized) <= ToolResultLimits.DETAIL_VALUE_MAX_BYTES) {
                return sanitized;
            }
            PersistedToolOutput persisted = artifactStore.persistJson(
                    toolCallId,
                    suffix,
                    sanitized,
                    ToolResultPreviewMode.HEAD
            );
            artifactRefs.add(artifactRef("tool_result_detail", suffix, persisted));
            return detailReference(persisted);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                sanitized.add(sanitizeDetails(list.get(i), toolCallId, suffix + "-" + i, artifactRefs));
            }
            if (jsonSize(sanitized) <= ToolResultLimits.DETAIL_VALUE_MAX_BYTES) {
                return List.copyOf(sanitized);
            }
            PersistedToolOutput persisted = artifactStore.persistJson(
                    toolCallId,
                    suffix,
                    sanitized,
                    ToolResultPreviewMode.HEAD
            );
            artifactRefs.add(artifactRef("tool_result_detail", suffix, persisted));
            return detailReference(persisted);
        }
        if (jsonSize(value) <= ToolResultLimits.DETAIL_VALUE_MAX_BYTES) {
            return value;
        }
        PersistedToolOutput persisted = artifactStore.persistJson(toolCallId, suffix, value, ToolResultPreviewMode.HEAD);
        artifactRefs.add(artifactRef("tool_result_detail", suffix, persisted));
        return detailReference(persisted);
    }

    private Map<String, Object> detailReference(PersistedToolOutput output) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("persisted", true);
        reference.put("path", output.path().toString());
        reference.put("originalSizeBytes", output.originalSizeBytes());
        reference.put("preview", output.preview());
        reference.put("hasMore", output.hasMore());
        reference.put("json", output.json());
        return reference;
    }

    private ToolResultArtifactRef artifactRef(String kind, String label, PersistedToolOutput output) {
        return new ToolResultArtifactRef(
                kind,
                label,
                output.path(),
                output.originalSizeBytes(),
                output.json() ? "application/json" : "text/plain",
                output.hasMore(),
                output.json()
        );
    }

    private ToolResultMessage ensureNonEmptyContent(ToolResultMessage message) {
        if (hasImage(message.messageContents())) {
            return message;
        }
        String text = MessageContents.text(message);
        if (!text.isBlank()) {
            return message;
        }
        String toolName = message.getToolName() == null || message.getToolName().isBlank()
                ? "tool"
                : message.getToolName();
        return copy(message, textContents("(" + toolName + " completed with no output)"), message.getDetails());
    }

    public static String buildPersistedOutputMessage(PersistedToolOutput output, ToolResultPreviewMode previewMode) {
        String previewLabel = previewMode == ToolResultPreviewMode.TAIL ? "last" : "first";
        return PERSISTED_OUTPUT_TAG
                + "\nOutput too large (" + ToolResultLimits.formatSize(output.originalSizeBytes()) + "). Full output saved to: "
                + output.path()
                + "\n\nPreview (" + previewLabel + " " + ToolResultLimits.formatSize(ToolResultLimits.PREVIEW_SIZE_BYTES) + "):\n"
                + output.preview()
                + (output.hasMore() ? "\n...\n" : "\n")
                + PERSISTED_OUTPUT_CLOSING_TAG;
    }

    private boolean hasImage(List<MessageContent> contents) {
        if (contents == null) {
            return false;
        }
        for (MessageContent content : contents) {
            if (content instanceof ImageContent) {
                return true;
            }
        }
        return false;
    }

    private boolean isPersistedOutput(String text) {
        return text != null && text.trim().startsWith(PERSISTED_OUTPUT_TAG);
    }

    private ToolResultMessage copy(
            ToolResultMessage message,
            List<MessageContent> contents,
            Object details
    ) {
        return ToolResultMessage.builder()
                .id(message.getId())
                .timestamp(message.getTimestamp())
                .contents(contents == null ? List.of() : List.copyOf(contents))
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .details(details)
                .isError(message.isError())
                .build();
    }

    private List<MessageContent> textContents(String text) {
        return List.of(TextContent.builder().text(text == null ? "" : text).build());
    }

    private ToolResultPolicy policy(Tool tool) {
        return tool == null ? ToolResultPolicy.defaultPolicy() : tool.resultPolicy();
    }

    private String safeToolCallId(ToolResultInput input) {
        ToolCallContent toolCall = input.toolCall();
        if (toolCall == null || toolCall.getToolCallId() == null || toolCall.getToolCallId().isBlank()) {
            return "tool-call";
        }
        return toolCall.getToolCallId();
    }

    private int jsonSize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private int byteLength(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Processed(
            ToolResultInput input,
            ToolResultPolicy policy,
            ToolResultMessage message,
            List<ToolResultArtifactRef> artifactRefs
    ) {
        private ProcessedToolResult result() {
            return new ProcessedToolResult(message, artifactRefs);
        }
    }

    private record Candidate(int index, long size) {
    }
}
