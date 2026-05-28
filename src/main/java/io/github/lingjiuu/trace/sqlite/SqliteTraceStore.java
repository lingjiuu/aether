package io.github.lingjiuu.trace.sqlite;

import io.github.lingjiuu.trace.AgentTraceStore;
import io.github.lingjiuu.trace.TraceArtifactRecord;
import io.github.lingjiuu.trace.TraceEventRecord;
import io.github.lingjiuu.trace.TraceRunDetail;
import io.github.lingjiuu.trace.TraceRunRecord;
import io.github.lingjiuu.trace.TraceSpanRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SqliteTraceStore implements AgentTraceStore {

    private static final Logger LOGGER = Logger.getLogger(SqliteTraceStore.class.getName());
    private static final int DEFAULT_QUEUE_CAPACITY = 512;

    private final Path dbPath;
    private final ArrayBlockingQueue<SqlOperation> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    public SqliteTraceStore(Path dbPath) {
        this(dbPath, DEFAULT_QUEUE_CAPACITY);
    }

    public SqliteTraceStore(Path dbPath, int queueCapacity) {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath must not be null");
        }
        this.dbPath = dbPath.toAbsolutePath().normalize();
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        initialize();
        this.worker = new Thread(this::runWorker, "aether-trace-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void appendRunStarted(TraceRunRecord run) {
        if (run == null) {
            return;
        }
        enqueue(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR REPLACE INTO agent_runs(
                      run_id, session_id, turn_id, turn, command_id, task_kind, cwd,
                      model_provider, model_id, status, started_at_ms, ended_at_ms,
                      duration_ms, error
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, run.runId());
                statement.setString(2, run.sessionId());
                statement.setString(3, run.turnId());
                setInteger(statement, 4, run.turn());
                statement.setString(5, run.commandId());
                statement.setString(6, run.taskKind());
                statement.setString(7, run.cwd());
                statement.setString(8, run.modelProvider());
                statement.setString(9, run.modelId());
                statement.setString(10, run.status());
                statement.setLong(11, run.startedAtMs());
                setLong(statement, 12, run.endedAtMs());
                setLong(statement, 13, run.durationMs());
                statement.setString(14, run.error());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void appendRunFinished(String runId, String status, long endedAtMs, long durationMs, String error) {
        enqueue(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE agent_runs
                    SET status = ?, ended_at_ms = ?, duration_ms = ?, error = ?
                    WHERE run_id = ?
                    """)) {
                statement.setString(1, status);
                statement.setLong(2, endedAtMs);
                statement.setLong(3, durationMs);
                statement.setString(4, error);
                statement.setString(5, runId);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void appendSpanStarted(TraceSpanRecord span) {
        if (span == null) {
            return;
        }
        enqueue(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR REPLACE INTO agent_spans(
                      span_id, run_id, parent_span_id, kind, name, status, started_at_ms,
                      ended_at_ms, duration_ms, input_json, output_json, error
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, span.spanId());
                statement.setString(2, span.runId());
                statement.setString(3, span.parentSpanId());
                statement.setString(4, span.kind());
                statement.setString(5, span.name());
                statement.setString(6, span.status());
                statement.setLong(7, span.startedAtMs());
                setLong(statement, 8, span.endedAtMs());
                setLong(statement, 9, span.durationMs());
                statement.setString(10, span.inputJson());
                statement.setString(11, span.outputJson());
                statement.setString(12, span.error());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void appendSpanFinished(String spanId, String status, long endedAtMs, long durationMs, String outputJson, String error) {
        enqueue(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE agent_spans
                    SET status = ?, ended_at_ms = ?, duration_ms = ?, output_json = ?, error = ?
                    WHERE span_id = ?
                    """)) {
                statement.setString(1, status);
                statement.setLong(2, endedAtMs);
                statement.setLong(3, durationMs);
                statement.setString(4, outputJson);
                statement.setString(5, error);
                statement.setString(6, spanId);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void appendEvent(TraceEventRecord event) {
        if (event == null) {
            return;
        }
        enqueue(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR REPLACE INTO agent_events(
                      event_id, run_id, span_id, sequence, type, payload_json, timestamp_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, event.eventId());
                statement.setString(2, event.runId());
                statement.setString(3, event.spanId());
                setLong(statement, 4, event.sequence());
                statement.setString(5, event.type());
                statement.setString(6, event.payloadJson());
                statement.setLong(7, event.timestampMs());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void appendArtifact(TraceArtifactRecord artifact) {
        if (artifact == null) {
            return;
        }
        enqueue(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR REPLACE INTO agent_artifacts(
                      artifact_id, run_id, span_id, kind, path, sha256, bytes, mime_type, created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, artifact.artifactId());
                statement.setString(2, artifact.runId());
                statement.setString(3, artifact.spanId());
                statement.setString(4, artifact.kind());
                statement.setString(5, artifact.path());
                statement.setString(6, artifact.sha256());
                setLong(statement, 7, artifact.bytes());
                statement.setString(8, artifact.mimeType());
                statement.setLong(9, artifact.createdAtMs());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void flush() {
        if (closed.get()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            queue.put(connection -> latch.countDown());
            if (!latch.await(10, TimeUnit.SECONDS)) {
                LOGGER.warning("Timed out flushing trace writer.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<TraceRunDetail> readRun(String runId) {
        flush();
        try (Connection connection = openConnection()) {
            TraceRunRecord run = readRun(connection, runId);
            if (run == null) {
                return Optional.empty();
            }
            return Optional.of(new TraceRunDetail(
                    run,
                    readSpans(connection, runId),
                    readEvents(connection, runId),
                    readArtifacts(connection, runId)
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read trace run " + runId, e);
        }
    }

    @Override
    public List<TraceRunRecord> listRuns(int limit) {
        flush();
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM agent_runs
                     ORDER BY started_at_ms DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                List<TraceRunRecord> runs = new ArrayList<>();
                while (rs.next()) {
                    runs.add(runRecord(rs));
                }
                return List.copyOf(runs);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list trace runs.", e);
        }
    }

    @Override
    public void close() {
        flush();
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.interrupt();
    }

    public long droppedWrites() {
        return dropped.get();
    }

    private void initialize() {
        try {
            Files.createDirectories(dbPath.getParent());
            setOwnerOnly(dbPath.getParent(), true);
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA journal_mode=WAL");
                statement.executeUpdate("PRAGMA busy_timeout=5000");
                statement.executeUpdate("""
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
                statement.executeUpdate("""
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
                statement.executeUpdate("""
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
                statement.executeUpdate("""
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
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_runs_session_turn ON agent_runs(session_id, turn)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_spans_run_started ON agent_spans(run_id, started_at_ms)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_spans_kind_name ON agent_spans(kind, name)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_spans_status ON agent_spans(status)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_events_run_sequence ON agent_events(run_id, sequence)");
            }
            if (Files.exists(dbPath)) {
                setOwnerOnly(dbPath, false);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize trace database " + dbPath, e);
        }
    }

    private void runWorker() {
        try (Connection connection = openConnection()) {
            while (!closed.get() || !queue.isEmpty()) {
                SqlOperation operation = queue.poll(2, TimeUnit.SECONDS);
                if (operation == null) {
                    continue;
                }
                try {
                    operation.execute(connection);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to write trace record.", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Trace writer stopped.", e);
        }
    }

    private void enqueue(SqlOperation operation) {
        if (operation == null || closed.get()) {
            return;
        }
        if (!queue.offer(operation)) {
            dropped.incrementAndGet();
        }
    }

    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private TraceRunRecord readRun(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM agent_runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? runRecord(rs) : null;
            }
        }
    }

    private List<TraceSpanRecord> readSpans(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_spans
                WHERE run_id = ?
                ORDER BY started_at_ms, span_id
                """)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<TraceSpanRecord> spans = new ArrayList<>();
                while (rs.next()) {
                    spans.add(spanRecord(rs));
                }
                return List.copyOf(spans);
            }
        }
    }

    private List<TraceEventRecord> readEvents(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_events
                WHERE run_id = ?
                ORDER BY timestamp_ms, sequence, event_id
                """)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<TraceEventRecord> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(eventRecord(rs));
                }
                return List.copyOf(events);
            }
        }
    }

    private List<TraceArtifactRecord> readArtifacts(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM agent_artifacts
                WHERE run_id = ?
                ORDER BY created_at_ms, artifact_id
                """)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                List<TraceArtifactRecord> artifacts = new ArrayList<>();
                while (rs.next()) {
                    artifacts.add(artifactRecord(rs));
                }
                return List.copyOf(artifacts);
            }
        }
    }

    private TraceRunRecord runRecord(ResultSet rs) throws Exception {
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

    private TraceSpanRecord spanRecord(ResultSet rs) throws Exception {
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

    private TraceEventRecord eventRecord(ResultSet rs) throws Exception {
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

    private TraceArtifactRecord artifactRecord(ResultSet rs) throws Exception {
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

    private static void setLong(PreparedStatement statement, int index, Long value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws Exception {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Long getLong(ResultSet rs, String column) throws Exception {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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

    private interface SqlOperation {
        void execute(Connection connection) throws Exception;
    }
}
