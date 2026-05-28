package io.github.lingjiuu.tool.result;

public record ToolResultPolicy(
        long maxResultSizeChars,
        boolean persistLargeText,
        boolean includeInAggregateBudget,
        ToolResultPreviewMode previewMode
) {

    public ToolResultPolicy {
        if (maxResultSizeChars <= 0) {
            throw new IllegalArgumentException("maxResultSizeChars must be positive");
        }
        previewMode = previewMode == null ? ToolResultPreviewMode.HEAD : previewMode;
    }

    public static ToolResultPolicy defaultPolicy() {
        return new ToolResultPolicy(
                ToolResultLimits.DEFAULT_MAX_RESULT_SIZE_CHARS,
                true,
                true,
                ToolResultPreviewMode.HEAD
        );
    }

    public static ToolResultPolicy withMaxResultSizeChars(long maxResultSizeChars) {
        return new ToolResultPolicy(maxResultSizeChars, true, true, ToolResultPreviewMode.HEAD);
    }

    public static ToolResultPolicy neverPersist() {
        return new ToolResultPolicy(Long.MAX_VALUE, false, false, ToolResultPreviewMode.HEAD);
    }

    public long effectiveThreshold() {
        if (maxResultSizeChars == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.min(maxResultSizeChars, ToolResultLimits.DEFAULT_MAX_RESULT_SIZE_CHARS);
    }
}
