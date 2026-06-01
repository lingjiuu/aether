package io.github.lingjiuu.trace.sqlite;

import io.github.lingjiuu.trace.TraceArtifactRecord;
import io.github.lingjiuu.trace.TraceEventRecord;
import io.github.lingjiuu.trace.TraceRunRecord;
import io.github.lingjiuu.trace.TraceSpanRecord;
import org.jdbi.v3.core.Handle;

final class SqliteTraceWriter {

    private SqliteTraceWriter() {
    }

    static void appendRunStarted(Handle handle, TraceRunRecord run) {
        handle.createUpdate("""
                INSERT OR REPLACE INTO agent_runs(
                  run_id, session_id, turn_id, turn, command_id, task_kind, cwd,
                  model_provider, model_id, status, started_at_ms, ended_at_ms,
                  duration_ms, error
                ) VALUES (
                  :runId, :sessionId, :turnId, :turn, :commandId, :taskKind, :cwd,
                  :modelProvider, :modelId, :status, :startedAtMs, :endedAtMs,
                  :durationMs, :error
                )
                """)
                .bind("runId", run.runId())
                .bind("sessionId", run.sessionId())
                .bind("turnId", run.turnId())
                .bind("turn", run.turn())
                .bind("commandId", run.commandId())
                .bind("taskKind", run.taskKind())
                .bind("cwd", run.cwd())
                .bind("modelProvider", run.modelProvider())
                .bind("modelId", run.modelId())
                .bind("status", run.status())
                .bind("startedAtMs", run.startedAtMs())
                .bind("endedAtMs", run.endedAtMs())
                .bind("durationMs", run.durationMs())
                .bind("error", run.error())
                .execute();
    }

    static void appendRunFinished(
            Handle handle,
            String runId,
            String status,
            long endedAtMs,
            long durationMs,
            String error
    ) {
        handle.createUpdate("""
                UPDATE agent_runs
                SET status = :status,
                    ended_at_ms = :endedAtMs,
                    duration_ms = :durationMs,
                    error = :error
                WHERE run_id = :runId
                """)
                .bind("status", status)
                .bind("endedAtMs", endedAtMs)
                .bind("durationMs", durationMs)
                .bind("error", error)
                .bind("runId", runId)
                .execute();

        String orphanSpanStatus = "COMPLETED".equals(status) ? "FAILED" : status;
        handle.createUpdate("""
                UPDATE agent_spans
                SET status = :status,
                    ended_at_ms = :endedAtMs,
                    duration_ms = MAX(0, :endedAtMs - started_at_ms),
                    error = CASE
                        WHEN error IS NULL OR error = '' THEN :error
                        ELSE error
                    END
                WHERE run_id = :runId
                  AND status = 'RUNNING'
                """)
                .bind("status", orphanSpanStatus)
                .bind("endedAtMs", endedAtMs)
                .bind("error", "span left running when run finished")
                .bind("runId", runId)
                .execute();
    }

    static void appendSpanStarted(Handle handle, TraceSpanRecord span) {
        handle.createUpdate("""
                INSERT OR REPLACE INTO agent_spans(
                  span_id, run_id, parent_span_id, kind, name, status, started_at_ms,
                  ended_at_ms, duration_ms, input_json, output_json, error
                ) VALUES (
                  :spanId, :runId, :parentSpanId, :kind, :name, :status, :startedAtMs,
                  :endedAtMs, :durationMs, :inputJson, :outputJson, :error
                )
                """)
                .bind("spanId", span.spanId())
                .bind("runId", span.runId())
                .bind("parentSpanId", span.parentSpanId())
                .bind("kind", span.kind())
                .bind("name", span.name())
                .bind("status", span.status())
                .bind("startedAtMs", span.startedAtMs())
                .bind("endedAtMs", span.endedAtMs())
                .bind("durationMs", span.durationMs())
                .bind("inputJson", span.inputJson())
                .bind("outputJson", span.outputJson())
                .bind("error", span.error())
                .execute();
    }

    static void appendSpanFinished(
            Handle handle,
            String spanId,
            String status,
            long endedAtMs,
            long durationMs,
            String outputJson,
            String error
    ) {
        handle.createUpdate("""
                UPDATE agent_spans
                SET status = :status,
                    ended_at_ms = :endedAtMs,
                    duration_ms = :durationMs,
                    output_json = :outputJson,
                    error = :error
                WHERE span_id = :spanId
                """)
                .bind("status", status)
                .bind("endedAtMs", endedAtMs)
                .bind("durationMs", durationMs)
                .bind("outputJson", outputJson)
                .bind("error", error)
                .bind("spanId", spanId)
                .execute();
    }

    static void appendEvent(Handle handle, TraceEventRecord event) {
        handle.createUpdate("""
                INSERT OR REPLACE INTO agent_events(
                  event_id, run_id, span_id, sequence, type, payload_json, timestamp_ms
                ) VALUES (
                  :eventId, :runId, :spanId, :sequence, :type, :payloadJson, :timestampMs
                )
                """)
                .bind("eventId", event.eventId())
                .bind("runId", event.runId())
                .bind("spanId", event.spanId())
                .bind("sequence", event.sequence())
                .bind("type", event.type())
                .bind("payloadJson", event.payloadJson())
                .bind("timestampMs", event.timestampMs())
                .execute();
    }

    static void appendArtifact(Handle handle, TraceArtifactRecord artifact) {
        handle.createUpdate("""
                INSERT OR REPLACE INTO agent_artifacts(
                  artifact_id, run_id, span_id, kind, path, sha256, bytes, mime_type, created_at_ms
                ) VALUES (
                  :artifactId, :runId, :spanId, :kind, :path, :sha256, :bytes, :mimeType, :createdAtMs
                )
                """)
                .bind("artifactId", artifact.artifactId())
                .bind("runId", artifact.runId())
                .bind("spanId", artifact.spanId())
                .bind("kind", artifact.kind())
                .bind("path", artifact.path())
                .bind("sha256", artifact.sha256())
                .bind("bytes", artifact.bytes())
                .bind("mimeType", artifact.mimeType())
                .bind("createdAtMs", artifact.createdAtMs())
                .execute();
    }
}
