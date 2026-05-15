package io.github.lingjiuu.tool.tools;

import junit.framework.TestCase;

public class TextOutputTruncatorTest extends TestCase {

    public void testTruncateHeadPreservesCompleteLines() {
        TextOutputTruncator.TruncationResult result = TextOutputTruncator.truncateHead("alpha\nbeta\ngamma", 9);

        assertTrue(result.truncated());
        assertEquals("alpha", result.content());
        assertEquals("bytes", result.truncatedBy());
        assertEquals(3, result.totalLines());
        assertFalse(result.firstLineExceedsLimit());
    }

    public void testTruncateHeadReturnsEmptyWhenFirstLineExceedsLimit() {
        TextOutputTruncator.TruncationResult result = TextOutputTruncator.truncateHead("0123456789", 5);

        assertTrue(result.truncated());
        assertEquals("", result.content());
        assertTrue(result.firstLineExceedsLimit());
    }

    public void testTruncateLineAddsNotice() {
        TextOutputTruncator.LineTruncation result = TextOutputTruncator.truncateLine("abcdef", 3);

        assertEquals("abc... [truncated]", result.text());
        assertTrue(result.wasTruncated());
    }

    public void testTruncateTailPreservesLastLines() {
        TextOutputTruncator.TruncationResult result = TextOutputTruncator.truncateTail("alpha\nbeta\ngamma", 2, 100);

        assertTrue(result.truncated());
        assertEquals("beta\ngamma", result.content());
        assertEquals("lines", result.truncatedBy());
        assertEquals(3, result.totalLines());
        assertEquals(2, result.outputLines());
        assertFalse(result.lastLinePartial());
    }

    public void testTruncateTailByBytesKeepsCompleteLinesWhenPossible() {
        TextOutputTruncator.TruncationResult result = TextOutputTruncator.truncateTail("alpha\nbeta\ngamma", 10, 9);

        assertTrue(result.truncated());
        assertEquals("gamma", result.content());
        assertEquals("bytes", result.truncatedBy());
        assertFalse(result.lastLinePartial());
    }

    public void testTruncateTailKeepsEndOfOversizedFinalLine() {
        TextOutputTruncator.TruncationResult result = TextOutputTruncator.truncateTail("0123456789", 10, 5);

        assertTrue(result.truncated());
        assertEquals("56789", result.content());
        assertEquals("bytes", result.truncatedBy());
        assertTrue(result.lastLinePartial());
    }

    public void testTruncateTailDoesNotSplitUtf8Characters() {
        TextOutputTruncator.TruncationResult result = TextOutputTruncator.truncateTail("你好世界", 10, 7);

        assertTrue(result.truncated());
        assertEquals("世界", result.content());
        assertTrue(result.lastLinePartial());
    }
}
