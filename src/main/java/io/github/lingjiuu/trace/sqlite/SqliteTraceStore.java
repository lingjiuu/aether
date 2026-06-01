package io.github.lingjiuu.trace.sqlite;

import io.github.lingjiuu.trace.AgentTraceStore;
import io.github.lingjiuu.trace.TraceArtifactRecord;
import io.github.lingjiuu.trace.TraceEventRecord;
import io.github.lingjiuu.trace.TraceRunDetail;
import io.github.lingjiuu.trace.TraceRunRecord;
import io.github.lingjiuu.trace.TraceSpanRecord;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlite3.SQLitePlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
    private static final long CRITICAL_ENQUEUE_TIMEOUT_SECONDS = 10L;

    private final Path dbPath;
    private final Jdbi jdbi;
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
        this.jdbi = createJdbi(this.dbPath);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        SqliteTraceSchema.initialize(this.dbPath, jdbi);
        this.worker = new Thread(this::runWorker, "aether-trace-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void appendRunStarted(TraceRunRecord run) {
        if (run == null) {
            return;
        }
        enqueueCritical(handle -> SqliteTraceWriter.appendRunStarted(handle, run));
    }

    @Override
    public void appendRunFinished(String runId, String status, long endedAtMs, long durationMs, String error) {
        enqueueCritical(handle -> SqliteTraceWriter.appendRunFinished(handle, runId, status, endedAtMs, durationMs, error));
    }

    @Override
    public void appendSpanStarted(TraceSpanRecord span) {
        if (span == null) {
            return;
        }
        enqueueCritical(handle -> SqliteTraceWriter.appendSpanStarted(handle, span));
    }

    @Override
    public void appendSpanFinished(
            String spanId,
            String status,
            long endedAtMs,
            long durationMs,
            String outputJson,
            String error
    ) {
        enqueueCritical(handle -> SqliteTraceWriter.appendSpanFinished(
                handle,
                spanId,
                status,
                endedAtMs,
                durationMs,
                outputJson,
                error
        ));
    }

    @Override
    public void appendEvent(TraceEventRecord event) {
        if (event == null) {
            return;
        }
        enqueue(handle -> SqliteTraceWriter.appendEvent(handle, event));
    }

    @Override
    public void appendArtifact(TraceArtifactRecord artifact) {
        if (artifact == null) {
            return;
        }
        enqueue(handle -> SqliteTraceWriter.appendArtifact(handle, artifact));
    }

    @Override
    public void flush() {
        if (closed.get()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        boolean interrupted = false;
        boolean enqueued = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!closed.get()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                try {
                    if (queue.offer(handle -> latch.countDown(), remainingNanos, TimeUnit.NANOSECONDS)) {
                        enqueued = true;
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (queue.offer(handle -> latch.countDown())) {
                        enqueued = true;
                        break;
                    }
                }
            }
            if (!enqueued) {
                LOGGER.warning("Timed out flushing trace writer.");
                return;
            }
            while (latch.getCount() > 0L) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    LOGGER.warning("Timed out flushing trace writer.");
                    return;
                }
                try {
                    latch.await(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Optional<TraceRunDetail> readRun(String runId) {
        flush();
        return jdbi.withHandle(handle -> {
            SqliteTraceSchema.applyConnectionPragmas(handle);
            return SqliteTraceReader.readRun(handle, runId);
        });
    }

    @Override
    public List<TraceRunRecord> listRuns(int limit) {
        flush();
        return jdbi.withHandle(handle -> {
            SqliteTraceSchema.applyConnectionPragmas(handle);
            return SqliteTraceReader.listRuns(handle, limit);
        });
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

    private void runWorker() {
        try (Handle handle = jdbi.open()) {
            SqliteTraceSchema.applyConnectionPragmas(handle);
            while (!closed.get() || !queue.isEmpty()) {
                SqlOperation operation = queue.poll(2, TimeUnit.SECONDS);
                if (operation == null) {
                    continue;
                }
                try {
                    operation.execute(handle);
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

    private void enqueueCritical(SqlOperation operation) {
        if (operation == null || closed.get()) {
            return;
        }
        boolean interrupted = false;
        boolean enqueued = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CRITICAL_ENQUEUE_TIMEOUT_SECONDS);
        try {
            while (!closed.get()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                try {
                    if (queue.offer(operation, remainingNanos, TimeUnit.NANOSECONDS)) {
                        enqueued = true;
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (queue.offer(operation)) {
                        enqueued = true;
                        break;
                    }
                }
            }
            if (!enqueued) {
                dropped.incrementAndGet();
                LOGGER.warning("Timed out enqueueing critical trace lifecycle record.");
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Jdbi createJdbi(Path dbPath) {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dbPath);
        jdbi.installPlugin(new SQLitePlugin());
        return jdbi;
    }

    private interface SqlOperation {
        void execute(Handle handle) throws Exception;
    }
}
