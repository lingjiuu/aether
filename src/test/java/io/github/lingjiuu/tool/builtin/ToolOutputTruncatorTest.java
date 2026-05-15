package io.github.lingjiuu.tool.builtin;

import junit.framework.TestCase;

public class ToolOutputTruncatorTest extends TestCase {

    public void testTruncateHeadPreservesCompleteLines() {
        ToolOutputTruncator.TruncationResult result = ToolOutputTruncator.truncateHead("alpha\nbeta\ngamma", 9);

        assertTrue(result.truncated());
        assertEquals("alpha", result.content());
        assertEquals("bytes", result.truncatedBy());
        assertEquals(3, result.totalLines());
        assertFalse(result.firstLineExceedsLimit());
    }

    public void testTruncateHeadReturnsEmptyWhenFirstLineExceedsLimit() {
        ToolOutputTruncator.TruncationResult result = ToolOutputTruncator.truncateHead("0123456789", 5);

        assertTrue(result.truncated());
        assertEquals("", result.content());
        assertTrue(result.firstLineExceedsLimit());
    }

    public void testTruncateLineAddsNotice() {
        ToolOutputTruncator.LineTruncation result = ToolOutputTruncator.truncateLine("abcdef", 3);

        assertEquals("abc... [truncated]", result.text());
        assertTrue(result.wasTruncated());
    }

    public void testTruncateTailPreservesLastLines() {
        ToolOutputTruncator.TruncationResult result = ToolOutputTruncator.truncateTail("alpha\nbeta\ngamma", 2, 100);

        assertTrue(result.truncated());
        assertEquals("beta\ngamma", result.content());
        assertEquals("lines", result.truncatedBy());
        assertEquals(3, result.totalLines());
        assertEquals(2, result.outputLines());
        assertFalse(result.lastLinePartial());
    }

    public void testTruncateTailByBytesKeepsCompleteLinesWhenPossible() {
        ToolOutputTruncator.TruncationResult result = ToolOutputTruncator.truncateTail("alpha\nbeta\ngamma", 10, 9);

        assertTrue(result.truncated());
        assertEquals("gamma", result.content());
        assertEquals("bytes", result.truncatedBy());
        assertFalse(result.lastLinePartial());
    }

    public void testTruncateTailKeepsEndOfOversizedFinalLine() {
        ToolOutputTruncator.TruncationResult result = ToolOutputTruncator.truncateTail("0123456789", 10, 5);

        assertTrue(result.truncated());
        assertEquals("56789", result.content());
        assertEquals("bytes", result.truncatedBy());
        assertTrue(result.lastLinePartial());
    }

    public void testTruncateTailDoesNotSplitUtf8Characters() {
        ToolOutputTruncator.TruncationResult result = ToolOutputTruncator.truncateTail("你好世界", 10, 7);

        assertTrue(result.truncated());
        assertEquals("世界", result.content());
        assertTrue(result.lastLinePartial());
    }
}
