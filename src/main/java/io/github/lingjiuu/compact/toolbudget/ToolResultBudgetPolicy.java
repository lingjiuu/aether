package io.github.lingjiuu.compact.toolbudget;

public record ToolResultBudgetPolicy(
        int maxCharsPerGroup,
        int previewChars
) {

    public ToolResultBudgetPolicy {
        if (maxCharsPerGroup <= 0) {
            throw new IllegalArgumentException("maxCharsPerGroup must be positive");
        }
        if (previewChars <= 0) {
            throw new IllegalArgumentException("previewChars must be positive");
        }
    }

    public static ToolResultBudgetPolicy defaultPolicy() {
        return new ToolResultBudgetPolicy(24_000, 2_000);
    }
}
