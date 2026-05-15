package io.github.lingjiuu.tool.builtin;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class BashOutputAccumulatorTest extends TestCase {

    public void testSnapshotWithoutTruncationDoesNotCreateTempFile() throws Exception {
        BashOutputAccumulator accumulator = new BashOutputAccumulator(10, 100);
        accumulator.append("alpha\nbeta");

        BashOutputAccumulator.Snapshot snapshot = accumulator.snapshot(true);

        assertEquals("alpha\nbeta", snapshot.content());
        assertFalse(snapshot.truncated());
        assertNull(snapshot.fullOutputPath());
    }

    public void testTruncatedSnapshotWritesFullOutputTempFile() throws Exception {
        BashOutputAccumulator accumulator = new BashOutputAccumulator(2, 100);
        accumulator.append("alpha\nbeta\ngamma");

        BashOutputAccumulator.Snapshot snapshot = accumulator.snapshot(true);

        assertTrue(snapshot.truncated());
        assertEquals("beta\ngamma", snapshot.content());
        assertNotNull(snapshot.fullOutputPath());
        assertEquals("alpha\nbeta\ngamma", Files.readString(snapshot.fullOutputPath()));
    }

    public void testSnapshotPrefersFinalLines() throws Exception {
        BashOutputAccumulator accumulator = new BashOutputAccumulator(3, 100);
        accumulator.append("one\ntwo\nthree\nfour\nfive");

        BashOutputAccumulator.Snapshot snapshot = accumulator.snapshot(false);

        assertTrue(snapshot.truncated());
        assertEquals("three\nfour\nfive", snapshot.content());
        assertNull(snapshot.fullOutputPath());
    }

    public void testAppendBytesUsesUtf8() throws Exception {
        BashOutputAccumulator accumulator = new BashOutputAccumulator(10, 100);
        byte[] bytes = "你好".getBytes(StandardCharsets.UTF_8);
        accumulator.append(bytes, bytes.length);

        BashOutputAccumulator.Snapshot snapshot = accumulator.snapshot(false);

        assertEquals("你好", snapshot.content());
    }
}
