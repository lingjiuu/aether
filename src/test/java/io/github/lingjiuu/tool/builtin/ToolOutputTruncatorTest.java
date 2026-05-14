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
}
