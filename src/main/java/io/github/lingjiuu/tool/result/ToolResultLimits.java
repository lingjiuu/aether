package io.github.lingjiuu.tool.result;

public final class ToolResultLimits {

    public static final long DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000L;
    public static final int PREVIEW_SIZE_BYTES = 2_000;
    public static final long MAX_TOOL_RESULTS_PER_BATCH_CHARS = 200_000L;
    public static final int DETAIL_VALUE_MAX_BYTES = 32_000;

    private ToolResultLimits() {
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
