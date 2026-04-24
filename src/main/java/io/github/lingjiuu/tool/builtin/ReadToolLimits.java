package io.github.lingjiuu.tool.builtin;

public record ReadToolLimits(int maxLines, int maxBytes) {

    private static final int DEFAULT_MAX_LINES = 2000;
    private static final int DEFAULT_MAX_BYTES = 64 * 1024;

    public ReadToolLimits {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
    }

    public static ReadToolLimits defaults() {
        return new ReadToolLimits(DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }
}
