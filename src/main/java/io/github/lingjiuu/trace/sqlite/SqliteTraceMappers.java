package io.github.lingjiuu.trace.sqlite;

import io.github.lingjiuu.trace.TraceArtifactRecord;
import io.github.lingjiuu.trace.TraceEventRecord;
import io.github.lingjiuu.trace.TraceRunRecord;
import io.github.lingjiuu.trace.TraceSpanRecord;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

final class SqliteTraceMappers {

    private SqliteTraceMappers() {
    }

    static TraceRunRecord runRecord(ResultSet rs, StatementContext context) throws SQLException {
        return new TraceRunRecord(
                rs.getString("run_id"),
                rs.getString("session_id"),
                rs.getString("turn_id"),
                getInteger(rs, "turn"),
                rs.getString("command_id"),
                rs.getString("task_kind"),
                rs.getString("cwd"),
                rs.getString("model_provider"),
                rs.getString("model_id"),
                rs.getString("status"),
                rs.getLong("started_at_ms"),
                getLong(rs, "ended_at_ms"),
                getLong(rs, "duration_ms"),
                rs.getString("error")
        );
    }

    static TraceSpanRecord spanRecord(ResultSet rs, StatementContext context) throws SQLException {
        return new TraceSpanRecord(
                rs.getString("span_id"),
                rs.getString("run_id"),
                rs.getString("parent_span_id"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getLong("started_at_ms"),
                getLong(rs, "ended_at_ms"),
                getLong(rs, "duration_ms"),
                rs.getString("input_json"),
                rs.getString("output_json"),
                rs.getString("error")
        );
    }

    static TraceEventRecord eventRecord(ResultSet rs, StatementContext context) throws SQLException {
        return new TraceEventRecord(
                rs.getString("event_id"),
                rs.getString("run_id"),
                rs.getString("span_id"),
                getLong(rs, "sequence"),
                rs.getString("type"),
                rs.getString("payload_json"),
                rs.getLong("timestamp_ms")
        );
    }

    static TraceArtifactRecord artifactRecord(ResultSet rs, StatementContext context) throws SQLException {
        return new TraceArtifactRecord(
                rs.getString("artifact_id"),
                rs.getString("run_id"),
                rs.getString("span_id"),
                rs.getString("kind"),
                rs.getString("path"),
                rs.getString("sha256"),
                getLong(rs, "bytes"),
                rs.getString("mime_type"),
                rs.getLong("created_at_ms")
        );
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
