package io.github.lingjiuu.trace.sqlite;

import io.github.lingjiuu.trace.TraceArtifactRecord;
import io.github.lingjiuu.trace.TraceEventRecord;
import io.github.lingjiuu.trace.TraceRunRecord;
import io.github.lingjiuu.trace.TraceSpanRecord;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class SqliteTraceStoreTest extends TestCase {

    public void testWritesAndReadsRunDetail() throws Exception {
        Path dbPath = Files.createTempDirectory("aether-trace-store-test").resolve("trace.sqlite");
        SqliteTraceStore store = new SqliteTraceStore(dbPath);
        try {
            store.appendRunStarted(new TraceRunRecord(
                    "run-1",
                    "session-1",
                    "turn-1",
                    1,
                    "command-1",
                    "REGULAR",
                    "/tmp/work",
                    "fake",
                    "fake-model",
                    "RUNNING",
                    10L,
                    null,
                    null,
                    null
            ));
            store.appendSpanStarted(new TraceSpanRecord(
                    "span-1",
                    "run-1",
                    null,
                    "model",
                    "model.sample",
                    "RUNNING",
                    11L,
                    null,
                    null,
                    "{\"input\":true}",
                    null,
                    null
            ));
            store.appendSpanFinished("span-1", "COMPLETED", 20L, 9L, "{\"output\":true}", null);
            store.appendEvent(new TraceEventRecord(
                    "event-1",
                    "run-1",
                    "span-1",
                    1L,
                    "ui.TURN_STARTED",
                    "{\"type\":\"TURN_STARTED\"}",
                    12L
            ));
            store.appendArtifact(new TraceArtifactRecord(
                    "artifact-1",
                    "run-1",
                    "span-1",
                    "tool_output",
                    "/tmp/full-output.txt",
                    "abc",
                    123L,
                    "text/plain",
                    13L
            ));
            store.appendRunFinished("run-1", "COMPLETED", 30L, 20L, null);

            var detail = store.readRun("run-1").orElse(null);
            assertNotNull(detail);
            assertEquals("COMPLETED", detail.run().status());
            assertEquals(1, detail.spans().size());
            assertEquals("COMPLETED", detail.spans().getFirst().status());
            assertEquals(1, detail.events().size());
            assertEquals("ui.TURN_STARTED", detail.events().getFirst().type());
            assertEquals(1, detail.artifacts().size());
            assertEquals("/tmp/full-output.txt", detail.artifacts().getFirst().path());
            assertTrue(Files.exists(dbPath));
        } finally {
            store.close();
        }
    }
}
