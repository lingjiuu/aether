package io.github.lingjiuu.trace.sqlite;

import io.github.lingjiuu.trace.TraceArtifactRecord;
import io.github.lingjiuu.trace.TraceEventRecord;
import io.github.lingjiuu.trace.TraceRunDetail;
import io.github.lingjiuu.trace.TraceRunRecord;
import io.github.lingjiuu.trace.TraceSpanRecord;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Optional;

final class SqliteTraceReader {

    private SqliteTraceReader() {
    }

    static Optional<TraceRunDetail> readRun(Handle handle, String runId) {
        Optional<TraceRunRecord> run = handle.createQuery("SELECT * FROM agent_runs WHERE run_id = :runId")
                .bind("runId", runId)
                .map(SqliteTraceMappers::runRecord)
                .findOne();
        return run.map(record -> new TraceRunDetail(
                record,
                readSpans(handle, runId),
                readEvents(handle, runId),
                readArtifacts(handle, runId)
        ));
    }

    static List<TraceRunRecord> listRuns(Handle handle, int limit) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return handle.createQuery("""
                SELECT * FROM agent_runs
                ORDER BY started_at_ms DESC
                LIMIT :limit
                """)
                .bind("limit", safeLimit)
                .map(SqliteTraceMappers::runRecord)
                .list();
    }

    private static List<TraceSpanRecord> readSpans(Handle handle, String runId) {
        return handle.createQuery("""
                SELECT * FROM agent_spans
                WHERE run_id = :runId
                ORDER BY started_at_ms, span_id
                """)
                .bind("runId", runId)
                .map(SqliteTraceMappers::spanRecord)
                .list();
    }

    private static List<TraceEventRecord> readEvents(Handle handle, String runId) {
        return handle.createQuery("""
                SELECT * FROM agent_events
                WHERE run_id = :runId
                ORDER BY timestamp_ms, sequence, event_id
                """)
                .bind("runId", runId)
                .map(SqliteTraceMappers::eventRecord)
                .list();
    }

    private static List<TraceArtifactRecord> readArtifacts(Handle handle, String runId) {
        return handle.createQuery("""
                SELECT * FROM agent_artifacts
                WHERE run_id = :runId
                ORDER BY created_at_ms, artifact_id
                """)
                .bind("runId", runId)
                .map(SqliteTraceMappers::artifactRecord)
                .list();
    }
}
