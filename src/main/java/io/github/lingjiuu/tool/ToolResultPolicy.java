package io.github.lingjiuu.tool;

public final class ToolResultPolicy {

    private static final int DEFAULT_MAX_TEXT_CHARS = 30_000;

    private final int maxTextChars;
    private final boolean truncate;
    private final String truncationNotice;

    private ToolResultPolicy(int maxTextChars, boolean truncate, String truncationNotice) {
        if (maxTextChars <= 0) {
            throw new IllegalArgumentException("maxTextChars must be positive");
        }
        this.maxTextChars = maxTextChars;
        this.truncate = truncate;
        this.truncationNotice = truncationNotice == null || truncationNotice.isBlank()
                ? "[Tool output truncated.]"
                : truncationNotice;
    }

    public static ToolResultPolicy defaults() {
        return new ToolResultPolicy(
                DEFAULT_MAX_TEXT_CHARS,
                true,
                "[Tool output truncated after " + DEFAULT_MAX_TEXT_CHARS + " characters.]"
        );
    }

    public static ToolResultPolicy maxTextChars(int maxTextChars) {
        return new ToolResultPolicy(
                maxTextChars,
                true,
                "[Tool output truncated after " + maxTextChars + " characters.]"
        );
    }

    public static ToolResultPolicy unbounded() {
        return new ToolResultPolicy(Integer.MAX_VALUE, false, "");
    }

    public int maxTextChars() {
        return maxTextChars;
    }

    public boolean truncate() {
        return truncate;
    }

    public String truncationNotice() {
        return truncationNotice;
    }
}
