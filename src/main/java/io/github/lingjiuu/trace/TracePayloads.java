package io.github.lingjiuu.trace;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TracePayloads {

    private static final int DEFAULT_PREVIEW_CHARS = 4096;

    private TracePayloads() {
    }

    public static Map<String, Object> modelInput(ModelRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageCount", request == null || request.getMessages() == null ? 0 : request.getMessages().size());
        payload.put("baseInstructionLength", length(request == null ? null : request.getBaseInstructions()));
        payload.put("toolNames", request == null || request.getTools() == null
                ? List.of()
                : request.getTools().stream().map(Tool::name).toList());
        if (request != null && request.getCallOptions() != null && request.getCallOptions().getReasoning() != null) {
            payload.put("reasoningEffort", request.getCallOptions().getReasoning().effortName());
        }
        return payload;
    }

    public static Map<String, Object> modelOutput(AssistantMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (message == null) {
            payload.put("aborted", true);
            return payload;
        }
        payload.put("responseId", message.getResponseId());
        payload.put("stopReason", message.getStopReason() == null ? null : message.getStopReason().name());
        payload.put("error", message.getErrorMessage());
        payload.put("contentCount", message.messageContents() == null ? 0 : message.messageContents().size());
        payload.put("usage", message.getUsage());
        payload.put("text", textPayload(MessageContents.text(message)));
        return payload;
    }

    public static Map<String, Object> toolInput(ToolCallContent toolCall) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (toolCall == null) {
            return payload;
        }
        payload.put("toolCallId", toolCall.getToolCallId());
        payload.put("toolName", toolCall.getToolName());
        payload.put("argumentsJson", textPayload(toolCall.getArgumentsJson()));
        return payload;
    }

    public static Map<String, Object> toolOutput(
            ToolExecutionResult result,
            String status,
            Long durationMs
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("durationMs", durationMs);
        if (result == null) {
            return payload;
        }
        payload.put("isError", result.isError());
        payload.put("details", result.getDetails());
        payload.put("truncated", truncated(result.getDetails()));
        payload.put("artifact", artifactRef(result.getDetails()));
        payload.put("text", textPayload(messageText(result)));
        return payload;
    }

    public static Map<String, Object> toolResultLink(
            ToolResultMessage message,
            String transcriptRecordId,
            String status,
            Long durationMs
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tool_result_model_visible");
        payload.put("transcriptRecordId", transcriptRecordId);
        payload.put("toolCallId", message == null ? null : message.getToolCallId());
        payload.put("toolName", message == null ? null : message.getToolName());
        payload.put("status", status);
        payload.put("durationMs", durationMs);
        payload.put("details", message == null ? null : message.getDetails());
        payload.put("truncated", message == null ? null : truncated(message.getDetails()));
        payload.put("artifact", message == null ? null : artifactRef(message.getDetails()));
        payload.put("text", textPayload(message == null ? null : MessageContents.text(message)));
        return payload;
    }

    public static Map<String, Object> conversationItem(
            String kind,
            Message message,
            String transcriptRecordId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("transcriptRecordId", transcriptRecordId);
        payload.put("role", message == null || message.role() == null ? null : message.role().name());
        payload.put("messageId", message == null ? null : message.id());
        payload.put("text", textPayload(message == null ? null : MessageContents.text(message)));
        return payload;
    }

    public static Map<String, Object> compactInput(String trigger, int originalMessageCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trigger", trigger);
        payload.put("originalMessageCount", originalMessageCount);
        return payload;
    }

    public static Map<String, Object> compactOutput(
            String status,
            int originalMessageCount,
            Integer replacementMessageCount,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("originalMessageCount", originalMessageCount);
        payload.put("replacementMessageCount", replacementMessageCount);
        payload.put("reason", reason);
        return payload;
    }

    public static Map<String, Object> textPayload(String text) {
        String value = text == null ? "" : text;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("preview", preview(value, DEFAULT_PREVIEW_CHARS));
        payload.put("length", value.length());
        payload.put("sha256", sha256(value));
        payload.put("truncatedForTrace", value.length() > DEFAULT_PREVIEW_CHARS);
        return payload;
    }

    private static String messageText(ToolExecutionResult result) {
        if (result == null || result.getContents() == null) {
            return "";
        }
        return MessageContents.text(ToolResultMessage.builder()
                .contents(result.getContents())
                .build());
    }

    private static String preview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static Object truncated(Object details) {
        if (details instanceof Map<?, ?> map && map.get("truncated") instanceof Boolean truncated) {
            return truncated;
        }
        return null;
    }

    private static Map<String, Object> artifactRef(Object details) {
        if (!(details instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        copyIfPresent(map, artifact, "artifactId");
        copyIfPresent(map, artifact, "artifactPath");
        copyIfPresent(map, artifact, "stdoutFullOutputPath");
        copyIfPresent(map, artifact, "stderrFullOutputPath");
        return artifact.isEmpty() ? null : artifact;
    }

    private static void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value.toString());
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}
