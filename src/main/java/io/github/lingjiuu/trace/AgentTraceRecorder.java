package io.github.lingjiuu.trace;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.task.TaskKind;
import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.result.ToolResultArtifactRef;
import io.github.lingjiuu.transcript.TranscriptRecord;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentTraceRecorder implements AutoCloseable {

    private static final AgentTraceRecorder NOOP = new AgentTraceRecorder(new NoOpTraceStore(), false);

    private final AgentTraceStore store;
    private final boolean enabled;
    private final ConcurrentHashMap<String, TraceContext> contextsByTurnId = new ConcurrentHashMap<>();

    public AgentTraceRecorder(AgentTraceStore store) {
        this(store, true);
    }

    private AgentTraceRecorder(AgentTraceStore store, boolean enabled) {
        this.store = store == null ? new NoOpTraceStore() : store;
        this.enabled = enabled;
    }

    public static AgentTraceRecorder noop() {
        return NOOP;
    }

    public TraceContext startRun(TurnContext turnContext, TaskKind taskKind, SessionConfig config) {
        if (!enabled || turnContext == null) {
            return disabledContext();
        }
        long startedAtMs = System.currentTimeMillis();
        String runId = TraceIds.runId();
        String provider = config == null || config.endpoint() == null ? null : config.endpoint().providerId();
        String modelId = config == null || config.model() == null ? null : config.model().getId();
        Path cwd = turnContext.cwd();
        TraceContext context = new TraceContext(
                runId,
                turnContext.sessionId(),
                turnContext.turnId() == null ? null : turnContext.turnId().value(),
                turnContext.turn(),
                turnContext.commandId(),
                taskKind == null ? null : taskKind.name(),
                cwd,
                provider,
                modelId
        );
        store.appendRunStarted(new TraceRunRecord(
                runId,
                context.sessionId(),
                context.turnId(),
                context.turn(),
                context.commandId(),
                context.taskKind(),
                cwd == null ? null : cwd.toString(),
                provider,
                modelId,
                "RUNNING",
                startedAtMs,
                null,
                null,
                null
        ));
        if (context.turnId() != null) {
            contextsByTurnId.put(context.turnId(), context);
        }
        return context;
    }

    public void finishRun(TraceContext context, String status, long startedAtMs, Throwable throwable) {
        if (!isEnabled(context)) {
            return;
        }
        long endedAtMs = System.currentTimeMillis();
        store.appendRunFinished(
                context.runId(),
                status == null ? "COMPLETED" : status,
                endedAtMs,
                Math.max(0L, endedAtMs - startedAtMs),
                throwable == null ? null : throwable.getMessage()
        );
        store.flush();
    }

    public TraceSpan startModelSpan(TraceContext context, ModelRequest request) {
        return startSpan(context, "model", "model.sample", TracePayloads.modelInput(request));
    }

    public TraceSpan startToolSpan(TraceContext context, ToolCallContent toolCall) {
        String name = toolCall == null || toolCall.getToolName() == null
                ? "tool"
                : "tool." + toolCall.getToolName();
        return startSpan(context, "tool", name, TracePayloads.toolInput(toolCall));
    }

    public TraceSpan startCompactSpan(TraceContext context, String trigger, int originalMessageCount) {
        return startSpan(context, "compact", "compact", TracePayloads.compactInput(trigger, originalMessageCount));
    }

    public TraceSpan startSpan(
            TraceContext context,
            String kind,
            String name,
            Map<String, Object> input
    ) {
        if (!isEnabled(context)) {
            return new TraceSpan(this, disabledContext(), null, System.currentTimeMillis());
        }
        long startedAtMs = System.currentTimeMillis();
        String spanId = TraceIds.spanId();
        store.appendSpanStarted(new TraceSpanRecord(
                spanId,
                context.runId(),
                null,
                kind,
                name,
                "RUNNING",
                startedAtMs,
                null,
                null,
                TraceJson.write(input),
                null,
                null
        ));
        return new TraceSpan(this, context, spanId, startedAtMs);
    }

    void finishSpan(TraceSpan span, String status, Map<String, Object> output, String error) {
        if (span == null || !span.enabled()) {
            return;
        }
        long endedAtMs = System.currentTimeMillis();
        store.appendSpanFinished(
                span.id(),
                status == null ? "COMPLETED" : status,
                endedAtMs,
                Math.max(0L, endedAtMs - span.startedAtMs()),
                TraceJson.write(output),
                error
        );
    }

    public void recordToolResult(
            TraceContext context,
            String spanId,
            ToolResultMessage message,
            TranscriptRecord transcriptRecord,
            List<ToolResultArtifactRef> artifactRefs,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs
    ) {
        if (!isEnabled(context)) {
            return;
        }
        long timestampMs = System.currentTimeMillis();
        List<Map<String, Object>> traceArtifacts = recordArtifacts(context, spanId, artifactRefs, timestampMs);
        appendEvent(
                context,
                spanId,
                null,
                "conversation.tool_result",
                TracePayloads.toolResultLink(
                        message,
                        transcriptRecord == null ? null : transcriptRecord.getId(),
                        traceArtifacts,
                        status,
                        durationMs,
                        approvalWaitMs,
                        executionDurationMs
                ),
                timestampMs
        );
    }

    public void recordConversationItem(
            TraceContext context,
            String kind,
            Message message,
            TranscriptRecord transcriptRecord
    ) {
        if (!isEnabled(context)) {
            return;
        }
        appendEvent(
                context,
                null,
                null,
                "conversation." + (kind == null ? "item" : kind),
                TracePayloads.conversationItem(
                        kind,
                        message,
                        transcriptRecord == null ? null : transcriptRecord.getId()
                ),
                System.currentTimeMillis()
        );
    }

    public void recordUiEvent(UiEvent event) {
        if (!enabled || event == null || event.getTurnId() == null || !shouldTraceUiEvent(event)) {
            return;
        }
        TraceContext context = contextsByTurnId.get(event.getTurnId());
        if (!isEnabled(context)) {
            return;
        }
        appendEvent(
                context,
                null,
                event.getSequence(),
                "ui." + (event.getType() == null ? "unknown" : event.getType().name()),
                uiPayload(event),
                event.getTimestampMs() == null ? System.currentTimeMillis() : event.getTimestampMs()
        );
    }

    public void recordToolExecutionOutput(
            TraceSpan span,
            ToolCallResult<?> result,
            String status,
            Long durationMs,
            Long approvalWaitMs,
            Long executionDurationMs
    ) {
        if (span == null || !span.enabled()) {
            return;
        }
        span.finish(
                status == null ? "COMPLETED" : status,
                TracePayloads.toolOutput(result, status, durationMs, approvalWaitMs, executionDurationMs)
        );
    }

    public AgentTraceStore store() {
        return store;
    }

    public void flush() {
        store.flush();
    }

    @Override
    public void close() {
        store.close();
    }

    private void appendEvent(
            TraceContext context,
            String spanId,
            Long sequence,
            String type,
            Map<String, Object> payload,
            long timestampMs
    ) {
        store.appendEvent(new TraceEventRecord(
                TraceIds.eventId(),
                context.runId(),
                spanId,
                sequence,
                type,
                TraceJson.write(payload),
                timestampMs
        ));
    }

    private List<Map<String, Object>> recordArtifacts(
            TraceContext context,
            String spanId,
            List<ToolResultArtifactRef> artifactRefs,
            long timestampMs
    ) {
        if (artifactRefs == null || artifactRefs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ToolResultArtifactRef ref : artifactRefs) {
            if (ref == null || ref.path() == null) {
                continue;
            }
            TraceArtifactRecord artifact = new TraceArtifactRecord(
                    TraceIds.artifactId(),
                    context.runId(),
                    spanId,
                    ref.kind(),
                    ref.path().toString(),
                    sha256(ref.path()),
                    ref.bytes(),
                    ref.mimeType(),
                    timestampMs
            );
            store.appendArtifact(artifact);
            payloads.add(artifactPayload(artifact, ref));
        }
        return payloads.isEmpty() ? List.of() : List.copyOf(payloads);
    }

    private Map<String, Object> artifactPayload(TraceArtifactRecord artifact, ToolResultArtifactRef ref) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactId", artifact.artifactId());
        payload.put("kind", artifact.kind());
        payload.put("label", ref.label());
        payload.put("path", artifact.path());
        payload.put("sha256", artifact.sha256());
        payload.put("bytes", artifact.bytes());
        payload.put("mimeType", artifact.mimeType());
        payload.put("hasMore", ref.hasMore());
        payload.put("json", ref.json());
        return payload;
    }

    private String sha256(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isEnabled(TraceContext context) {
        return enabled && context != null && context.enabled();
    }

    private boolean shouldTraceUiEvent(UiEvent event) {
        if (event.getType() == null) {
            return false;
        }
        return switch (event.getType()) {
            case ASSISTANT_TEXT_DELTA,
                    REASONING_DELTA,
                    TOOL_CALL_ARGUMENTS_DELTA -> false;
            default -> true;
        };
    }

    private Map<String, Object> uiPayload(UiEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.getType() == null ? null : event.getType().name());
        payload.put("payload", event.getPayload());
        return payload;
    }

    private TraceContext disabledContext() {
        return new TraceContext(null, null, null, null, null, null, null, null, null);
    }
}
