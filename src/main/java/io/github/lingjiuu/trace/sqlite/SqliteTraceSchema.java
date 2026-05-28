package io.github.lingjiuu.trace.sqlite;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class SqliteTraceSchema {

    private SqliteTraceSchema() {
    }

    static void initialize(Path dbPath, Jdbi jdbi) {
        try {
            Files.createDirectories(dbPath.getParent());
            setOwnerOnly(dbPath.getParent(), true);
            jdbi.useHandle(handle -> {
                applyConnectionPragmas(handle);
                handle.execute("PRAGMA journal_mode=WAL");
                handle.execute("""
                        CREATE TABLE IF NOT EXISTS agent_runs(
                          run_id TEXT PRIMARY KEY,
                          session_id TEXT NOT NULL,
                          turn_id TEXT,
                          turn INTEGER,
                          command_id TEXT,
                          task_kind TEXT NOT NULL,
                          cwd TEXT,
                          model_provider TEXT,
                          model_id TEXT,
                          status TEXT NOT NULL,
                          started_at_ms INTEGER NOT NULL,
                          ended_at_ms INTEGER,
                          duration_ms INTEGER,
                          error TEXT
                        )
                        """);
                handle.execute("""
                        CREATE TABLE IF NOT EXISTS agent_spans(
                          span_id TEXT PRIMARY KEY,
                          run_id TEXT NOT NULL,
                          parent_span_id TEXT,
                          kind TEXT NOT NULL,
                          name TEXT NOT NULL,
                          status TEXT NOT NULL,
                          started_at_ms INTEGER NOT NULL,
                          ended_at_ms INTEGER,
                          duration_ms INTEGER,
                          input_json TEXT,
                          output_json TEXT,
                          error TEXT
                        )
                        """);
                handle.execute("""
                        CREATE TABLE IF NOT EXISTS agent_events(
                          event_id TEXT PRIMARY KEY,
                          run_id TEXT NOT NULL,
                          span_id TEXT,
                          sequence INTEGER,
                          type TEXT NOT NULL,
                          payload_json TEXT,
                          timestamp_ms INTEGER NOT NULL
                        )
                        """);
                handle.execute("""
                        CREATE TABLE IF NOT EXISTS agent_artifacts(
                          artifact_id TEXT PRIMARY KEY,
                          run_id TEXT NOT NULL,
                          span_id TEXT,
                          kind TEXT NOT NULL,
                          path TEXT NOT NULL,
                          sha256 TEXT,
                          bytes INTEGER,
                          mime_type TEXT,
                          created_at_ms INTEGER NOT NULL
                        )
                        """);
                handle.execute("CREATE INDEX IF NOT EXISTS idx_agent_runs_session_turn ON agent_runs(session_id, turn)");
                handle.execute("CREATE INDEX IF NOT EXISTS idx_agent_spans_run_started ON agent_spans(run_id, started_at_ms)");
                handle.execute("CREATE INDEX IF NOT EXISTS idx_agent_spans_kind_name ON agent_spans(kind, name)");
                handle.execute("CREATE INDEX IF NOT EXISTS idx_agent_spans_status ON agent_spans(status)");
                handle.execute("CREATE INDEX IF NOT EXISTS idx_agent_events_run_sequence ON agent_events(run_id, sequence)");
            });
            if (Files.exists(dbPath)) {
                setOwnerOnly(dbPath, false);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize trace database " + dbPath, e);
        }
    }

    static void applyConnectionPragmas(Handle handle) {
        handle.execute("PRAGMA busy_timeout=5000");
    }

    private static void setOwnerOnly(Path path, boolean directory) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            Set<PosixFilePermission> permissions = directory
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
        } catch (Exception ignored) {
        }
    }
}
